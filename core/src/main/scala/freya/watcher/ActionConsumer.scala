package freya.watcher

import cats.data.OptionT
import cats.effect.ConcurrentEffect
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.ExitCodes.ConsumerExitCode
import freya.errors.{OperatorError, ParseReconcileError, ParseResourceError, WatcherClosedError}
import freya.internal.AnsiColors.{gr, re, xx}
import freya.internal.kubeapi.CrdApi.StatusUpdate
import freya.models.{CustomResource, NewStatus}
import freya.watcher.AbstractWatcher.{Channel, NamespaceQueue}
import freya.watcher.actions._
import freya.{Controller, ExitCodes}
import io.fabric8.kubernetes.client.Watcher.Action.{ADDED, DELETED, ERROR, MODIFIED}

class ActionConsumer[F[_], T, U](
  key: String,
  val controller: Controller[F, T, U],
  val kind: String,
  queue: NamespaceQueue[T, U],
  capacity: Int,
  channel: Channel[F, T, U],
  feedback: Option[FeedbackConsumerAlg[F, U]]
)(implicit F: ConcurrentEffect[F])
    extends LazyLogging {

  private val noStatus = F.pure[NewStatus[U]](None)

  private[freya] def putAction(action: Either[OperatorError, OperatorAction[T, U]]): F[Unit] =
    if (queue.length < capacity)
      queue.enqueue(action).pure[F] >> channel.put(action)
    else
      F.delay(logger.debug(s"Queue of '$key' consumer is full(${queue.length}), waiting for space")) >> channel.put(
        action
      ) >> putAction(action)

  private[freya] def consume: F[ConsumerExitCode] =
    for {
      ec <- if (queue.nonEmpty) processAction(queue.dequeue()) else channel.take >> consume
    } yield ec

  private def processAction(action: Either[OperatorError, OperatorAction[T, U]]): F[ConsumerExitCode] =
    for {
      _ <- F.delay(logger.debug(s"consuming action $action"))
      ec <- action match {
        case Right(a) =>
          updateStatus(a.resource, handleAction(a)) *> consume
        case Left(e) =>
          handleError(e)
      }
    } yield ec

  private def handleError(e: OperatorError) =
    e match {
      case WatcherClosedError(e) =>
        F.delay(logger.error(s"K8s closed socket, so closing consumer $key as well", e)) *>
          feedback.fold(F.unit)(f => f.put(Left(()))) *> ExitCodes.ActionConsumerExitCode.pure[F]
      case ParseResourceError(a, t, r) =>
        F.delay(logger.error(s"Failed action $a for resource $r", t)) *> consume
      case ParseReconcileError(t, r) =>
        F.delay(logger.error(s"Failed 'reconcile' action for resource $r", t)) *> consume
    }

  private def handleAction(oAction: OperatorAction[T, U]): F[NewStatus[U]] =
    (oAction match {
      case ServerAction(wAction, resource) =>
        wAction match {
          case ADDED =>
            F.delay(
              logger
                .debug(
                  s"Event received ${gr}ADDED$xx kind=$kind name=${resource.metadata.name} in '${resource.metadata.namespace}' namespace"
                )
            ) *>
              controller.onAdd(resource) <*
              F.delay(
                logger.debug(s"Event ${gr}ADDED$xx for kind=$kind name=${resource.metadata.name} has been handled")
              )

          case DELETED =>
            F.delay(
              logger
                .debug(
                  s"Event received ${gr}DELETED$xx kind=$kind name=${resource.metadata.name} in '${resource.metadata.namespace}' namespace"
                )
            ) *>
              controller.onDelete(resource) *>
              F.delay(
                logger.debug(s"Event ${gr}DELETED$xx for kind=$kind name=${resource.metadata.name} has been handled")
              ) *> F.pure(Option.empty[U])

          case MODIFIED =>
            F.delay(
              logger
                .debug(
                  s"Event received ${gr}MODIFIED$xx kind=$kind name=${resource.metadata.name} in '${resource.metadata.namespace}' namespace"
                )
            ) *>
              controller.onModify(resource) <*
              F.delay(
                logger.debug(s"Event ${gr}MODIFIED$xx for kind=$kind name=${resource.metadata.name} has been handled")
              )

          case ERROR =>
            F.delay(
              logger.error(
                s"Event received ${re}ERROR$xx for kind=$kind name=${resource.metadata.name} in '${resource.metadata.namespace}' namespace"
              )
            ) *> noStatus
        }
      case ReconcileAction(resource) =>
        controller.reconcile(resource)

    }).handleErrorWith(e => F.delay(logger.error(s"Controller failed to handle action: $oAction", e)) *> noStatus)

  private def updateStatus(cr: CustomResource[T, U], status: F[NewStatus[U]]): F[Unit] =
    (for {
      s <- OptionT(status)
      f <- feedback.toOptionT[F]
      _ <- OptionT(f.put(StatusUpdate[U](cr.metadata, s).asRight[Unit]).map(_.some))
    } yield ()).value.void
}
