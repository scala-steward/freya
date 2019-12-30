package freya

import cats.effect.ExitCase.Canceled
import cats.effect.concurrent.MVar
import cats.effect.syntax.all._
import cats.effect.{ConcurrentEffect, ExitCode, Resource, Sync, Timer}
import cats.implicits._
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.scalalogging.LazyLogging
import freya.Configuration.CrdConfig
import freya.Controller.ConfigMapController
import freya.Retry.{Infinite, Times}
import freya.errors.OperatorError
import freya.internal.AnsiColors._
import freya.internal.OperatorUtils._
import freya.internal.crd.Deployer
import freya.resource.{ConfigMapParser, CrdParser, Labels}
import freya.signals.{ConsumerSignal, OperatorSignal}
import freya.watcher.AbstractWatcher.CloseableWatcher
import freya.watcher._
import freya.watcher.actions.OperatorAction
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition
import io.fabric8.kubernetes.client._
import io.fabric8.kubernetes.client.utils.Serialization

import scala.concurrent.duration.DurationLong
import scala.util.Random

trait CrdWatchMaker[F[_], T] {
  def make(context: CrdWatcherContext[F, T]): WatcherMaker[F]
}

object CrdWatchMaker {
  implicit def crd[F[_]: ConcurrentEffect, T]: CrdWatchMaker[F, T] =
    (context: CrdWatcherContext[F, T]) => new CustomResourceWatcher(context)
}

trait ConfigMapWatchMaker[F[_], T] {
  def make(context: ConfigMapWatcherContext[F, T]): WatcherMaker[F]
}

object ConfigMapWatchMaker {
  implicit def cm[F[_]: ConcurrentEffect, T]: ConfigMapWatchMaker[F, T] =
    (context: ConfigMapWatcherContext[F, T]) => new ConfigMapWatcher(context)
}

trait CrdDeployer[F[_], T] {
  def deployCrd(client: KubernetesClient, cfg: CrdConfig[T], isOpenShift: Option[Boolean]): F[CustomResourceDefinition]
}

object CrdDeployer {
  implicit def deployer[F[_]: Sync, T]: CrdDeployer[F, T] =
    (client: KubernetesClient, cfg: CrdConfig[T], isOpenShift: Option[Boolean]) =>
      Deployer.deployCrd(client, cfg, isOpenShift)
}

object Operator extends LazyLogging {

  def ofCrd[F[_]: ConcurrentEffect: Timer, T](
    cfg: CrdConfig[T],
    client: F[KubernetesClient],
    controller: Controller[F, T]
  )(implicit watchMaker: CrdWatchMaker[F, T], deployer: CrdDeployer[F, T]): Operator[F, T] =
    ofCrd[F, T](cfg, client)((_: CrdHelper[F, T]) => controller)

  def ofCrd[F[_], T](cfg: CrdConfig[T], client: F[KubernetesClient])(controller: CrdHelper[F, T] => Controller[F, T])(
    implicit F: ConcurrentEffect[F],
    T: Timer[F],
    watchMaker: CrdWatchMaker[F, T],
    deployer: CrdDeployer[F, T]
  ): Operator[F, T] = {

    val pipeline = for {
      c <- client
      isOpenShift <- checkEnvAndConfig(c, cfg)
      crd <- deployer.deployCrd(c, cfg, isOpenShift)
      channel <- MVar[F].empty[Either[OperatorError[T], OperatorAction[T]]]
      parser <- CrdParser()

      helper = new CrdHelper[F, T](cfg, c, isOpenShift, crd, parser)
      ctl = controller(helper)
      context = CrdWatcherContext(
        cfg.namespace,
        cfg.getKind,
        new Consumer[F, T](ctl, cfg.getKind),
        CrdHelper.convertCr(cfg.forKind, parser),
        channel,
        c,
        crd
      )

      w <- F.delay(watchMaker.make(context).watch)
      reconciler = new Reconciler(cfg.reconcile, channel, F.delay(helper.currentResources))
    } yield createPipeline(helper, ctl, w, reconciler)

    new Operator[F, T](pipeline)
  }

  def ofConfigMap[F[_]: ConcurrentEffect: Timer, T](
    cfg: Configuration.ConfigMapConfig[T],
    client: F[KubernetesClient],
    controller: ConfigMapController[F, T]
  )(implicit watchMaker: ConfigMapWatchMaker[F, T]): Operator[F, T] =
    ofConfigMap[F, T](cfg, client)((_: ConfigMapHelper[F, T]) => controller)

  def ofConfigMap[F[_], T](cfg: Configuration.ConfigMapConfig[T], client: F[KubernetesClient])(
    controller: ConfigMapHelper[F, T] => ConfigMapController[F, T]
  )(implicit F: ConcurrentEffect[F], T: Timer[F], watchMaker: ConfigMapWatchMaker[F, T]): Operator[F, T] = {

    val pipeline = for {
      k8sClient <- client
      isOpenShift <- checkEnvAndConfig(k8sClient, cfg)
      channel <- MVar[F].empty[Either[OperatorError[T], OperatorAction[T]]]
      parser <- ConfigMapParser()

      helper = new ConfigMapHelper[F, T](cfg, k8sClient, isOpenShift, parser)
      ctl = controller(helper)
      context = ConfigMapWatcherContext(
        cfg.namespace,
        cfg.getKind,
        ctl,
        new Consumer[F, T](ctl, cfg.getKind),
        ConfigMapHelper.convertCm(cfg.forKind, parser),
        channel,
        k8sClient,
        Labels.forKind(cfg.getKind, cfg.prefix)
      )

      w <- F.delay(watchMaker.make(context).watch)
      reconciler = new Reconciler[F, T](cfg.reconcile, channel, F.delay(helper.currentConfigMaps))
    } yield createPipeline(helper, ctl, w, reconciler)

    new Operator[F, T](pipeline)
  }

  private def createPipeline[T, F[_]: ConcurrentEffect](
    helper: AbstractHelper[F, T],
    controller: Controller[F, T],
    watcher: F[(CloseableWatcher, F[ConsumerSignal])],
    reconciler: Reconciler[F, T]
  ) =
    OperatorPipeline[F, T](helper, watcher, reconciler, controller.onInit())

  private def checkEnvAndConfig[F[_]: Sync, T](client: KubernetesClient, cfg: Configuration[T]): F[Option[Boolean]] =
    for {
      _ <- Sync[F].fromEither(cfg.validate.leftMap(new RuntimeException(_)))
      check <- if (cfg.checkK8sOnStartup) checkKubeEnv(client) else Option.empty[Boolean].pure[F]
    } yield check

  private def checkKubeEnv[T, F[_]: Sync](client: KubernetesClient) =
    Sync[F].delay {
      val (onOpenShift, code) = checkIfOnOpenshift(client.getMasterUrl)
      if (onOpenShift) logger.debug(s"Returned code: $code. We are on OpenShift.")
      else logger.debug(s"Returned code: $code. We are not on OpenShift. Assuming, we are on Kubernetes.")
      onOpenShift.some
    }
}

private case class OperatorPipeline[F[_], T](
  helper: AbstractHelper[F, T],
  consumer: F[(CloseableWatcher, F[ConsumerSignal])],
  reconciler: Reconciler[F, T],
  onInit: F[Unit]
)

class Operator[F[_], T] private (pipeline: F[OperatorPipeline[F, T]])(implicit F: ConcurrentEffect[F])
    extends LazyLogging {

  def run: F[ExitCode] =
    Resource
      .make(start) {
        case (_, consumer) =>
          F.delay(consumer.close()) *> F.delay(logger.info(s"${re}Operator stopped$xx"))
      }
      .use {
        case (signal, _) => signal.map(_.fold(identity, identity))
      }
      .recoverWith {
        case e =>
          F.delay(logger.error("Got error while running an operator", e)) *> ExitCode.Error.pure[F]
      }

  def withRestart(retry: Retry = Infinite())(implicit T: Timer[F]): F[ExitCode] =
    run.flatMap(loop(_, retry))

  private def loop(ec: ExitCode, retry: Retry)(implicit T: Timer[F]): F[ExitCode] = {
    val (canRestart, delay, nextRetry, remaining) = retry match {
      case Times(maxRetries, delay, multiplier) =>
        (
          maxRetries > 0,
          delay,
          F.delay[Retry](Times(maxRetries - 1, Retry.nextDelay(delay, multiplier), multiplier)),
          maxRetries.toString
        )
      case i @ Infinite(minDelay, maxDelay) =>
        val minSeconds = minDelay.toSeconds
        (true, (Random.nextLong(maxDelay.toSeconds - minSeconds) + minSeconds).seconds, F.pure[Retry](i), "infinite")
    }
    if (canRestart)
      for {
        _ <- F.delay(logger.info(s"Sleeping for $delay"))
        _ <- T.sleep(delay)
        _ <- F.delay(logger.info(s"${re}Going to restart$xx. Restarts left: $remaining"))
        r <- nextRetry
        code <- withRestart(r)
      } yield code
    else ec.pure[F]
  }

  def start: F[(F[OperatorSignal], CloseableWatcher)] =
    (for {
      pipe <- pipeline
      _ <- F.delay(Serialization.jsonMapper().registerModule(DefaultScalaModule))

      name = pipe.helper.cfg.getKind
      namespace = pipe.helper.targetNamespace

      _ <- F.delay(logger.info(s"Starting operator $ye$name$xx for namespace $namespace"))
      _ <- pipe.onInit
      (closableWatcher, consumer) <- pipe.consumer
      _ <- F
        .delay(
          logger
            .info(s"${gr}Operator $name was started$xx in namespace '$namespace'")
        )
      _ <- F.delay(logger.info(s"${gr}Starting reconciler $name$xx in namespace '$namespace'"))
      reconciler = pipe.reconciler.run.guaranteeCase {
        case Canceled => F.delay(logger.debug("Reconciler was canceled!"))
        case _ => F.unit
      }
    } yield (F.race(consumer, reconciler), closableWatcher)).onError {
      case ex: Throwable =>
        F.delay(logger.error(s"Unable to start operator", ex))
    }
}
