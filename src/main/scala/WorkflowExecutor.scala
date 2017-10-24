package workflow

import scala.concurrent.{ExecutionContext, Future}
import play.api.Logger
import play.api.mvc.{Request, RequestHeader, Result, WebSocket}

import scala.language.reflectiveCalls
import scala.language.existentials

object WorkflowExecutor {

  private val logger = Logger("application.workflow.WorkflowExecutor")

  private object ResultsImpl extends play.api.mvc.Results

  import implicits._

  private def mkWorkflowContext[A,B](conf: WorkflowConf[A], label: String, previousLabel: Option[String], optB: Option[B]): WorkflowContext[B] = {
    val actionCurrent = conf.router.post(label)
    WorkflowContext(
      actionCurrent  = actionCurrent,
      actionPrevious = previousLabel.map(conf.router.post(_)),
      stepObject     = optB,
      restart        = conf.router.get(conf.start),
      goto           = s => conf.router.get(s)
    )
  }

  /** Will execute a workflow and return an Action result. The GET request will be
   *  directed to the indicated stepId.
   *
   *  @param conf the configuration defining the workflow
   *  @param stepId the current step position. A stepId value of {{{WorkflowConf.start}}} will
             clear the session and redirect to the first stepId in the workflow.
   */
  def getWorkflow[A](conf: WorkflowConf[A], stepId: String)(implicit request: Request[Any], ec: ExecutionContext): Future[Result] = {
    logger.debug(s"getWorkflow $stepId")
    if (stepId == conf.start) {
      nextLabel(conf.workflow).map {
        case Some(initialStep) => conf.dataStorage.withNewSession(ResultsImpl.Redirect(conf.router.get(initialStep).url, request.queryString))
        case None              => sys.error("empty flow!")
      }
    } else {
      doGet(conf, stepId, None, conf.workflow).flatMap {
        case Some(r) => Future(r)
        case None    => postWorkflow(conf, stepId)
      }
    }
  }

  // is tail recursive since recursion happens asynchronously
  private def doGet[A](conf: WorkflowConf[A], targetLabel: String, previousLabel: Option[String], remainingWf: Workflow[A])
      (implicit request: Request[Any], ec: ExecutionContext): Future[Option[Result]] = {
    logger.debug(s"doGet $targetLabel, $previousLabel")
    fold[A, B forSome {type B}, Option[Result]](conf, targetLabel, previousLabel, remainingWf)(
      {
        case (ws, optB, ctx) =>
          ws.step.get(ctx)(request)
      },
      { case (ws, optB, ctx) =>
          optB match {
            case Some(b) => val nextPreviousLabel = if (ws.cache) previousLabel else Some(ws.label)
                            doGet(conf, targetLabel, nextPreviousLabel, ws.next(b))
            case None    => logger.warn(s"client requested doGet $targetLabel, but they only have data for ${ws.label}")
                            Future(Some(ResultsImpl.Redirect(ctx.actionCurrent)))
          }
      }
    )
  }

  /** Will execute a workflow and return an Action result. The POST request will be
   *  directed to the indicated stepId.
   *
   *  @param conf the configuration defining the workflow
   *  @param stepId the current step position.
   */
  def postWorkflow[A](conf: WorkflowConf[A], stepId: String)(implicit request: Request[Any], ec: ExecutionContext): Future[Result] = {
    logger.debug(s"postWorkflow $stepId")
    doPost(conf, stepId, None, conf.workflow)
  }

  // is tail recursive since recursion happens asynchronously
  private def doPost[A](conf: WorkflowConf[A], targetLabel: String, previousLabel: Option[String], remainingWf: Workflow[A])(implicit request: Request[Any], ec: ExecutionContext): Future[Result] = {
    logger.debug(s"doPost $targetLabel, $previousLabel")
    fold[A, B forSome {type B}, Result](conf, targetLabel, previousLabel, remainingWf)(
      {
         case (ws, optB, ctx) =>
           ws.step.post(ctx)(request).flatMap {
             case Left(r)  => logger.debug(s"${ws.label} returning result"); Future(r)
             case Right(a) => logger.debug(s"putting ${ws.label} -> $a in session")
                              nextLabel(ws.next(a)).map {
                                case Some(next) => logger.debug(s"redirecting to $next")
                                                   conf.dataStorage.withUpdatedSession(
                                                     ResultsImpl.Redirect(mkWorkflowContext(conf, next, Some(ws.label), optB).actionCurrent),
                                                     ws.label,
                                                     ws.serialiser.serialise(a))
                                case None       => sys.error("doPost: flow finished!")
                              }
           }
      },
      { case (ws, optB, ctx) =>
          optB match {
            case Some(b) => val nextPreviousLabel = if (ws.cache) previousLabel else Some(ws.label)
                            doPost(conf, targetLabel, nextPreviousLabel, ws.next(b))
            case None    => logger.warn(s"client requested doPost $targetLabel, but they only have data for ${ws.label}")
                            Future(ResultsImpl.Redirect(ctx.actionCurrent))
          }
      }
    )
  }

  private type WS[A,B] = scala.concurrent.Future[Either[play.api.mvc.Result,(play.api.libs.iteratee.Enumerator[A], play.api.libs.iteratee.Iteratee[B,Unit]) => Unit]]

  /** Will execute a workflow and return an Action result. The websocket request will be
   *  directed to the indicated stepId. A Runtime exception will be thrown if the Step does not
   *  support a websocket request.
   *
   *  @param conf the configuration defining the workflow
   *  @param stepId the current step position.
   */
  def wsWorkflow[A](conf: WorkflowConf[A], currentLabel: String)(implicit request: RequestHeader, ec: ExecutionContext): WS[String, String] = {
    logger.debug(s"wsWorkflow $currentLabel")
    doWs(conf, currentLabel, None, conf.workflow).flatMap {
      case WebSocket(f) => f(request)
    }
  }

  // is tail recursive since recursion happens asynchronously
  private def doWs[A](conf: WorkflowConf[A], targetLabel: String, previousLabel: Option[String], remainingWf: Workflow[A])(implicit request: RequestHeader, ec: ExecutionContext): Future[WebSocket[String, String]] = {
    logger.debug(s"doWs $targetLabel, $previousLabel")
    fold[A, B forSome {type B}, WebSocket[String,String]](conf, targetLabel, previousLabel, remainingWf)(
      {
         case (ws, optB, ctx) =>
           ws.step.ws(ctx)(request).map(_.getOrElse(sys.error(s"No ws defined for step ${ws.label}")))
      },
      {
        case (ws, optB, ctx) =>
          optB match {
            case Some(b) => val nextPreviousLabel = if (ws.cache) previousLabel else Some(ws.label)
                            doWs(conf, targetLabel, nextPreviousLabel, ws.next(b))
            case None    => sys.error(s"client requested doWs $targetLabel, but they only have data for ${ws.label}")
          }
      }
    )
  }

  private def fold[A, B, C]
      (conf: WorkflowConf[A], targetLabel: String, previousLabel: Option[String], remainingWf: Workflow[A])
      (f: (WorkflowSyntax.WSStep[B, Workflow[A]], Option[B], WorkflowContext[B]) => Future[C],
       g: (WorkflowSyntax.WSStep[B, Workflow[A]], Option[B], WorkflowContext[B]) => Future[C])
      (implicit request: RequestHeader, ec: ExecutionContext): Future[C] = {
    remainingWf.resume.flatMap {
      case Right(a) => sys.error("doGet: flow finished!") // flow has finished (only will happen if last step has a post)
      case Left(ws: WorkflowSyntax.WSStep[B @unchecked, Workflow[A] @unchecked]) => // TODO avoid unchecked?
        val optB = dataFor[B](ws.label, conf.dataStorage, ws.serialiser)
        lazy val ctx = mkWorkflowContext(conf, ws.label, previousLabel, optB)
        if (ws.label == targetLabel) f(ws, optB, ctx)
        else                         g(ws, optB, ctx)
    }
  }

  private def dataFor[A](label: String, dataStorage: DataStorage, serialiser: Serialiser[A])(implicit request: RequestHeader): Option[A] =
    dataStorage.readData(label).flatMap(serialiser.deserialise(_))

  private def nextLabel[A](wf: Workflow[A])(implicit ec: ExecutionContext): Future[Option[String]] = {
    wf.resume.map {
      case Left(ws: WorkflowSyntax.WSStep[_, _]) => Some(ws.label)
      case err                                   => None
    }
  }
}
