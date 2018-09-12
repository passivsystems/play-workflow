package workflow

import org.scalatest.AsyncFlatSpec
import play.api.mvc.Call
import scala.concurrent.ExecutionContext

abstract class UnitSpec extends AsyncFlatSpec {

  def init(workflow: Workflow[Unit]) = {
    val dataStorage = new InMemoryDataStorage

    val conf = WorkflowConf[Unit](
        workflow    = workflow
      , dataStorage = dataStorage
      , router      = new {
                        def post(stepKey: String): Call = Call("POST", stepKey)
                        def get (stepKey: String): Call = Call("GET",  stepKey)
                      }
      )

    (conf, dataStorage)
  }

  def get[A](conf: WorkflowConf[A], step: String, queryString: String = "") =
    WorkflowExecutor.getWorkflow(conf, step)(
        request = play.api.test.FakeRequest("GET", s"/$step$queryString")
      , ec      = implicitly[ExecutionContext]
      )

  def post[A](conf: WorkflowConf[A], step: String) =
    WorkflowExecutor.postWorkflow(conf, step)(
        request = play.api.test.FakeRequest("POST", s"/$step")
      , ec      = implicitly[ExecutionContext]
      )
}
