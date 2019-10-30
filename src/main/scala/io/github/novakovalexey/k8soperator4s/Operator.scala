package io.github.novakovalexey.k8soperator4s

import java.net.URL

import cats.effect.{ConcurrentEffect, ExitCode, Resource, Sync}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import fs2.Stream
import fs2.concurrent.Queue
import io.fabric8.kubernetes.client.utils.HttpClientUtils
import io.fabric8.kubernetes.client.{Watch, _}
import io.github.novakovalexey.k8soperator4s.common.AnsiColors._
import io.github.novakovalexey.k8soperator4s.common._
import okhttp3.{HttpUrl, Request}

import scala.util.Try

object Operator extends LazyLogging {

  def checkIfOnOpenshift(masterURL: URL): Boolean =
    Try {
      val urlBuilder = new HttpUrl.Builder().host(masterURL.getHost)

      if (masterURL.getPort == -1) urlBuilder.port(masterURL.getDefaultPort)
      else urlBuilder.port(masterURL.getPort)

      if (masterURL.getProtocol == "https") urlBuilder.scheme("https")

      val url = urlBuilder.addPathSegment("apis/route.openshift.io/v1").build()

      val httpClient = HttpClientUtils.createHttpClient(new ConfigBuilder().build)
      val response = httpClient.newCall(new Request.Builder().url(url).build).execute
      response.body().close()
      httpClient.connectionPool().evictAll()
      val success = response.isSuccessful

      if (success) logger.debug(s"$url returned ${response.code}. We are on OpenShift.")
      else logger.debug(s"$url returned ${response.code}. We are not on OpenShift. Assuming, we are on Kubernetes.")

      success
    }.fold(e => {
      logger.error("Failed to distinguish between Kubernetes and OpenShift", e)
      logger.warn("Let's assume we are on Kubernetes")
      false
    }, identity)

  def ofCrd[F[_], T](
    controller: Controller[F, T],
    cfg: CrdConfig[T],
    client: KubernetesClient = new DefaultKubernetesClient()
  )(implicit F: ConcurrentEffect[F]): Operator[F, T] = {

    val operator: F[AbstractOperator[F, T]] = for {
      isOpenShift <- checkEnvAndConfig(client, cfg)
      crd <- CrdOperator.deployCrd(client, cfg, isOpenShift)
      q <- Queue.unbounded[F, OperatorEvent[T]]
      op <- F.delay(new CrdOperator[F, T](controller, cfg, client, isOpenShift, crd, q))
    } yield op

    new Operator[F, T](operator, client)
  }

  def ofConfigMap[F[_], T](
    controller: ConfigMapController[F, T],
    cfg: ConfigMapConfig[T],
    client: KubernetesClient = new DefaultKubernetesClient()
  )(implicit F: ConcurrentEffect[F]): Operator[F, T] = {

    val operator: F[AbstractOperator[F, T]] = for {
      isOpenShift <- checkEnvAndConfig(client, cfg)
      q <- Queue.unbounded[F, OperatorEvent[T]]
      op <- F.delay(new ConfigMapOperator[F, T](controller, cfg, client, isOpenShift, q))
    } yield op

    new Operator[F, T](operator, client)
  }

  private def checkEnvAndConfig[F[_]: Sync, T](client: KubernetesClient, cfg: OperatorCfg[T]): F[Boolean] =
    Sync[F].fromEither(cfg.validate.leftMap(new RuntimeException(_))) *> Sync[F].delay(
      Operator.checkIfOnOpenshift(client.getMasterUrl)
    )
}

private[k8soperator4s] class Operator[F[_], T](operator: F[AbstractOperator[F, T]], client: KubernetesClient)(
  implicit F: ConcurrentEffect[F]
) extends LazyLogging {

  trait StopHandler {
    def stop(): F[Unit]
  }

  private case class OperatorMeta(name: String, namespace: Namespaces)

  private def operatorMeta(operator: AbstractOperator[F, T]): OperatorMeta = {
    val name = operator.cfg.customKind.getOrElse(operator.cfg.forKind.getSimpleName)
    val namespace =
      if (operator.cfg.namespace == CurrentNamespace) operator.clientNamespace
      else operator.cfg.namespace
    OperatorMeta(name, namespace)
  }

  def run: F[ExitCode] =
    Resource
      .make(start) {
        case (_, s) =>
          s.stop *> F.delay(logger.info(s"operator stopped"))
      }
      .use {
        case (s, _) =>
          s.compile.drain.as(ExitCode.Success)
      }

  def start: F[(Stream[F, Unit], StopHandler)] =
    for {
      op <- operator
      meta = operatorMeta(op)

      _ <- F.delay {
        if (op.isOpenShift) logger.info(s"${ye}OpenShift$xx environment detected.")
        else logger.info(s"${ye}Kubernetes$xx environment detected.")
      }
      (watch, stream) <- F.defer(startOperator(op, meta))
      _ <- F
        .delay(
          logger
            .info(s"${re}Operator ${meta.name}$xx was started in namespace '${meta.namespace}'")
        )
        .onError {
          case ex: Throwable =>
            F.delay(logger.error(s"Unable to start operator for ${meta.namespace} namespace", ex))
        }
    } yield (stream, stopHandler(watch))

  private def stopHandler(watch: Watch): StopHandler =
    () => F.delay(watch.close()) *> F.delay(client.close())

  private def startOperator(operator: AbstractOperator[F, T], meta: OperatorMeta): F[(Watch, Stream[F, Unit])] =
    for {
      _ <- F.delay(logger.info(s"Starting operator ${meta.name} for namespace ${meta.namespace}"))
      _ <- operator.onInit()
      ws <- operator.watch
      _ <- F.delay(logger.info(s"Operaotr $gr${meta.name} running$xx for namespace ${meta.namespace}"))
    } yield ws

}
