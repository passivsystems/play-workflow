package workflow

import scala.concurrent.{ExecutionContext, Future}
import play.api.Logger
import play.api.mvc.{Request, RequestHeader, Result, WebSocket}

import scala.language.reflectiveCalls


object WorkflowExecutor {

  private val logger = Logger("application.workflow.WorkflowExecutor")

  private object ResultsImpl extends play.api.mvc.Results

  import implicits._

  private def mkWorkflowContext[A](wfc: WorkflowConf[A], label: String, previousLabel: Option[String], optB: Option[A]): WorkflowContext[A] = {
    val actionCurrent = wfc.router.post(label)
    WorkflowContext(
      actionCurrent  = actionCurrent,
      actionPrevious = previousLabel.map(wfc.router.post(_)),
      stepObject     = optB,
      restart        = wfc.router.get("start"),
      goto           = s => wfc.router.get(s)
    )
  }

  /** Will execute a workflow and return an Action result. The GET request will be
   *  directed to the indicated stepId.
   *
   *  @param wfc the configuration defining the workflow
   *  @param stepId the current step position. A stepId value of {{{"start"}}} will
             clear the session and redirect to the first stepId in the workflow.
   */
  def getWorkflow[A](wfc: WorkflowConf[A], stepId: String)(implicit request: Request[Any], ec: ExecutionContext): Future[Result] = {
    logger.debug(s"getWorkflow $stepId")
    if (stepId == "start") {
      nextLabel(wfc.workflow).map {
        case Some(initialStep) => wfc.dataStorage.withNewSession(ResultsImpl.Redirect(wfc.router.get(initialStep).url, request.queryString))
        case None              => sys.error("empty flow!")
      }
    } else {
      doGet(wfc, stepId, None, wfc.workflow).flatMap {
        case Some(r) => Future(r)
        case None    => postWorkflow(wfc, stepId)
      }
    }
  }

  // TODO refactor commonality between doGet/doPost - trouble with existential type
  //      also the unchecked type in match - required for compilation (we know they match)
  // is tail recursive since recursion happend asynchronously
  private def doGet[A](wfc: WorkflowConf[A], targetLabel: String, previousLabel: Option[String], remainingWf: Workflow[A])(implicit request: Request[Any], ec: ExecutionContext): Future[Option[Result]] = {
    logger.debug(s"doGet $targetLabel, $previousLabel")
    remainingWf.resume.flatMap {
      case Right(a) => sys.error("doGet: flow finished!") // flow has finished (only will happen if last step has a post)
      case Left(ws: WorkflowSyntax.WSStep[A @unchecked,_]) =>
        if (ws.label == targetLabel) {
          val optB = optDataFor(ws.label, wfc.dataStorage, ws.serialiser)
          val ctx = mkWorkflowContext(wfc, ws.label, previousLabel, optB)
          ws.step.get(ctx)(request)
        } else {
          val b = dataFor(ws.label, wfc.dataStorage, ws.serialiser)
          val nextPreviousLabel = if (ws.cache) previousLabel else Some(ws.label)
          doGet(wfc, targetLabel, nextPreviousLabel, ws.next(b))
        }
    }
  }

  /** Will execute a workflow and return an Action result. The POST request will be
   *  directed to the indicated stepId.
   *
   *  @param wfc the configuration defining the workflow
   *  @param stepId the current step position.
   */
  def postWorkflow[A](wfc: WorkflowConf[A], stepId: String)(implicit request: Request[Any], ec: ExecutionContext): Future[Result] = {
    logger.debug(s"postWorkflow $stepId")
    doPost(wfc, stepId, None, wfc.workflow)
  }

  // is tail recursive since recursion happend asynchronously
  private def doPost[A](wfc: WorkflowConf[A], targetLabel: String, previousLabel: Option[String], remainingWf: Workflow[A])(implicit request: Request[Any], ec: ExecutionContext): Future[Result] = {
    logger.debug(s"doPost $targetLabel, $previousLabel")
    remainingWf.resume.flatMap {
      case Right(a) => sys.error("doPost: flow finished!") // flow has finished (only will happen if last step has a post)
      case Left(ws: WorkflowSyntax.WSStep[A @unchecked,_]) =>
        if (ws.label == targetLabel) {
          val optB = optDataFor(ws.label, wfc.dataStorage, ws.serialiser)
          val ctx = mkWorkflowContext(wfc, ws.label, previousLabel, optB)
          ws.step.post(ctx)(request).flatMap {
            case Left(r)  => logger.warn(s"$ws.label returning result"); Future(r)
            case Right(a) => logger.warn(s"putting ${ws.label} -> $a in session")
                             nextLabel(ws.next(a)).map {
                               case Some(next) => logger.warn(s"redirecting to $next")
                                                  wfc.dataStorage.withUpdatedSession(
                                                    ResultsImpl.Redirect(mkWorkflowContext(wfc, next, previousLabel, optB).actionCurrent),
                                                    ws.label,
                                                    ws.serialiser.serialise(a))
                               case None       => sys.error("doPost: flow finished!")
                             }
          }
        } else {
          val b = dataFor(ws.label, wfc.dataStorage, ws.serialiser)
          val nextPreviousLabel = if (ws.cache) previousLabel else Some(ws.label)
          doPost(wfc, targetLabel, nextPreviousLabel, ws.next(b))
        }
    }
  }

  private type WS[A,B] = scala.concurrent.Future[Either[play.api.mvc.Result,(play.api.libs.iteratee.Enumerator[A], play.api.libs.iteratee.Iteratee[B,Unit]) => Unit]]

  /** Will execute a workflow and return an Action result. The websocket request will be
   *  directed to the indicated stepId. A Runtime exception will be thrown if the Step does not
   *  support a websocket request.
   *
   *  @param wfc the configuration defining the workflow
   *  @param stepId the current step position.
   */
  def wsWorkflow[A](wfc: WorkflowConf[A], currentLabel: String)(implicit request: RequestHeader, ec: ExecutionContext): WS[String, String] = {
    logger.debug(s"wsWorkflow $currentLabel")
    doWs(wfc, currentLabel, None, wfc.workflow).flatMap {
      case WebSocket(f) => f(request)
    }
  }

  // is tail recursive since recursion happend asynchronously
  private def doWs[A](wfc: WorkflowConf[A], targetLabel: String, previousLabel: Option[String], remainingWf: Workflow[A])(implicit request: RequestHeader, ec: ExecutionContext): Future[WebSocket[String, String]] = {
    logger.debug(s"doWs $targetLabel, $previousLabel")
    remainingWf.resume.flatMap {
      case Right(a) => sys.error("doWs: flow finished!") // flow has finished (only will happen if last step has a post)
      case Left(ws: WorkflowSyntax.WSStep[A @unchecked,_]) =>
        if (ws.label == targetLabel) {
          val ctx = mkWorkflowContext(wfc, ws.label, previousLabel, None)
          ws.step.ws(ctx)(request).map(_.getOrElse(sys.error(s"No ws defined for step ${ws.label}")))
        } else {
          val b = dataFor(ws.label, wfc.dataStorage, ws.serialiser)
          val nextPreviousLabel = if (ws.cache) previousLabel else Some(ws.label)
          Right((wfc, targetLabel, nextPreviousLabel, ws.next(b)))
          doWs(wfc, targetLabel, nextPreviousLabel, ws.next(b))
        }
    }
  }

  private def dataFor[A](label: String, dataStorage: DataStorage, serialiser: Serialiser[A])(implicit request: RequestHeader): A =
    optDataFor(label, dataStorage, serialiser).getOrElse(sys.error(s"invalid state - should have stored result for step $label"))

  private def optDataFor[A](label: String, dataStorage: DataStorage, serialiser: Serialiser[A])(implicit request: RequestHeader): Option[A] =
    dataStorage.readData(label).flatMap(serialiser.deserialise(_))

  private def nextLabel[A](wf: Workflow[A])(implicit ec: ExecutionContext): Future[Option[String]] = {
    wf.resume.map {
      case Left(ws: WorkflowSyntax.WSStep[_, _]) => Some(ws.label)
      case err                                   => None
    }
  }
}
