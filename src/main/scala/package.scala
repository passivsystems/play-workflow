import scala.concurrent.Future
import cats.Functor
import cats.free.FreeT
import play.api.mvc.{Call, Request, RequestHeader, Result, WebSocket}


// TODO if client already has imported Future implicits (e.g. cats.implicits._) then we will get conflicts
//      get client to explicitly import?
package object workflow extends cats.instances.FutureInstances {

  case class WorkflowConf[A](
    workflow:    Workflow[A],
    router:      {def post(stepKey: String): Call; def get(stepKey: String): Call}
  )

  case class WorkflowContext[A] private [workflow] (
    actionCurrent:  Call,
    actionPrevious: Option[Call],
    stepObject:     Option[A],
    restart:        Call,
    goto:           String => Call // TODO can we avoid this? maybe return Option[Call] ?
  )

  /** A single step in a Workflow.
   *  @constructor create a new Step with get, post and optionally ws.
   *  @param get defines the steps initial loading. If returns None, then the initial request will go straight to {{{post}}}.
   *  @param post defines the steps processing. This may involve form validation, which may return a Result, if failed, or
   *              a value to be stored in the session, and made accessible to the following steps.
   *  @param ws optionally, steps may support WebSockets. The websockets will have access
   *            to the context, and any step data just as get and post. However it is expected
   *            that a post will be made to advance the workflow.
   */
 // TODO both get and ws are optional - conform the type?
  case class Step[A](
    get:  WorkflowContext[A] => Request[Any] => Future[Option[Result]],
    post: WorkflowContext[A] => Request[Any] => Future[Either[Result, A]],
    ws:   Option[WorkflowContext[A] => RequestHeader => WebSocket[String, String]] = None // TODO support any input, output (not just String) if a reader and writer are provided.
  )

  private [workflow] sealed trait WorkflowSyntax[+Next]
  object WorkflowSyntax {
    case class WSStep[A, Next](label: String, step: Step[A], reader: upickle.default.Reader[A], writer: upickle.default.Writer[A], next: A => Next) extends WorkflowSyntax[Next]
  }

  private [workflow] implicit val workflowSyntaxFunctor: Functor[WorkflowSyntax] = new Functor[WorkflowSyntax] {
    def map[A, B](fa: WorkflowSyntax[A])(f: A => B): WorkflowSyntax[B] = fa match {
      case ws: WorkflowSyntax.WSStep[_, A] => WorkflowSyntax.WSStep(ws.label, ws.step, ws.reader, ws.writer, ws.next andThen f)
    }
  }

  /** Defines a sequence of steps to be executed */
  type Workflow[A] = FreeT[WorkflowSyntax, Future, A]
}
