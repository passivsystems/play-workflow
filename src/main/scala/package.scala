import scala.concurrent.Future
import cats.Functor
import cats.free.FreeT


package object workflow {

  private [workflow] implicit val workflowSyntaxFunctor: Functor[WorkflowSyntax] = new Functor[WorkflowSyntax] {
    def map[A, B](fa: WorkflowSyntax[A])(f: A => B): WorkflowSyntax[B] = fa match {
      case ws: WorkflowSyntax.WSStep[_, A] => WorkflowSyntax.WSStep(ws.label, ws.step, ws.serialiser, ws.cache, ws.next andThen f)
    }
  }

  /** Defines a sequence of steps to be executed */
  type Workflow[A] = FreeT[WorkflowSyntax, Future, A]

  /** Defines a single step, producing an A */
  // normally default F would be Id - is this wrong?
  type Step[A] = StepT[Future, A]
}
