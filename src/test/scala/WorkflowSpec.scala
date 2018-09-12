package workflow

import play.api.mvc.Results
import scala.concurrent.Future

class WorkflowSpec
  extends UnitSpec
     with DefaultSerialiser
     with Results {

  import cats.implicits._

  val workflow: Workflow[Unit] =
    for {
      res1 <- Workflow.step("step1", Step[String](
                  get  = ctx => request => Future(Some(Ok("get1")))
                , post = ctx => request => Future(Right("result1"))
                ))
      _    <- Workflow.step("step2", Step[String](
                  get  = ctx => request => Future(Some(Ok("get2")))
                , post = ctx => request => Future(Right("result2"))
                ))
    } yield ()



  "Initially" should "return GET step1" in {
    val (conf, dataStorage) = init(workflow)
    get(conf, "step1")
      .map { res =>
        assert(res == Ok("get1"))
        assert(dataStorage.getData == Map())
        assert(dataStorage.getInitParams == Map())
      }
  }

  it should "redirect to GET step1 when no data for step2" in {
    val (conf, dataStorage) = init(workflow)
    for {
      res1 <- get(conf, "step2")
      _ = assert(res1 == Redirect("step1"))

      res2 <- post(conf, "step2")
      _ = assert(res2 == Redirect("step1"))
    } yield succeed
  }

  it should "redirect to GET step1 when requesting a non-existing step" in {
    val (conf, dataStorage) = init(workflow)
    for {
      res1 <- get(conf, "step3")
      _  = assert(res1 == Redirect("step1"))

      res2 <- post(conf, "step3")
      _ = assert(res2 == Redirect("step1"))
    } yield succeed
  }

  it should "redirect to GET step1 when requesting start" in {
    val (conf, dataStorage) = init(workflow)
    get(conf, "start")
      .map(res => assert(res == Redirect("step1")))
  }

  it should "set any initial params when requesting start" in {
    val (conf, dataStorage) = init(workflow)
    get(conf, "start", "?a=1&b=2")
      .map { res =>
        assert(res == Redirect("step1"))
        assert(dataStorage.getData == Map())
        assert(dataStorage.getInitParams == Map("a" -> "1", "b" -> "2"))
      }
  }

  it should "not set any initial params when requesting intial step by name" in {
    val (conf, dataStorage) = init(workflow)
    get(conf, "step1", "?a=1&b=2")
      .map { res =>
        assert(res == Ok("get1"))
        assert(dataStorage.getData == Map())
        assert(dataStorage.getInitParams == Map())
      }
  }

  "After successful post to step 1" should "redirect to GET step2" in {
    val (conf, dataStorage) = init(workflow)
    post(conf, "step1")
      .map { res =>
        assert(res == Redirect("step2"))
        assert(dataStorage.getData == Map("step1" -> serialiser.serialise("result1")))
      }
  }

  it should "restart and clear data when requesting start" in {
    val (conf, dataStorage) = init(workflow)
    for {
      res1 <- post(conf, "step1")
      _ = assert(res1 == Redirect("step2"))
      _ = assert(dataStorage.getData == Map("step1" -> serialiser.serialise("result1")))

      res2 <- get(conf, "start")
      _ = assert(res2 == Redirect("step1"))
      _ = assert(dataStorage.getData == Map())
    } yield succeed
  }

  it should "redirect to GET step2 when requesting a non-existing step" in {
    val (conf, dataStorage) = init(workflow)
    for {
      res1 <- post(conf, "step1")
      _ = assert(res1 == Redirect("step2"))
      _ = assert(dataStorage.getData == Map("step1" -> serialiser.serialise("result1")))

      res2 <- get(conf, "step3")
      _ = assert(res2 == Redirect("step2"))
      _ = assert(dataStorage.getData == Map("step1" -> serialiser.serialise("result1")))
    } yield succeed
  }

  it should "restart and preserve initial params when requesting restart" in {
    val (conf, dataStorage) = init(workflow)
    for {
      res1 <- get(conf, "start", "?a=1&b=2")
      _ = assert(res1 == Redirect("step1"))
      _ = assert(dataStorage.getData == Map())
      _ = assert(dataStorage.getInitParams == Map("a" -> "1", "b" -> "2"))

      res2 <- post(conf, "step1")
      _ = assert(res2 == Redirect("step2"))
      _ = assert(dataStorage.getData == Map("step1" -> serialiser.serialise("result1")))

      res3 <- get(conf, "restart")
      _ = assert(res3 == Redirect("step1"))
      _ = assert(dataStorage.getData == Map())
      _ = assert(dataStorage.getInitParams == Map())
    } yield succeed
  }

  "A workflow with no get" should "execute the post when requesting get" in {
    val workflow: Workflow[Unit] =
      for {
        res1 <- Workflow.step("step1", Step[String](
                    get  = ctx => request => Future(None)
                  , post = ctx => request => Future(Right("result1"))
                  ))
        _    <- Workflow.step("step2", Step[String](
                    get  = ctx => request => Future(Some(Ok("get2")))
                  , post = ctx => request => Future(Right("result2"))
                  ))
      } yield ()

      val (conf, dataStorage) = init(workflow)

      get(conf, "step1")
        .map { res =>
          assert(res == Redirect("step2"))
          assert(dataStorage.getData == Map("step1" -> serialiser.serialise("result1")))
          assert(dataStorage.getInitParams == Map())
        }
    }

  "A post which fails" should "execute the post" in {
    val workflow: Workflow[Unit] =
      for {
        res1 <- Workflow.step("step1", Step[String](
                    get  = ctx => request => Future(None)
                  , post = ctx => request => Future(Left(NotFound("stay")))
                  ))
        _    <- Workflow.step("step2", Step[String](
                    get  = ctx => request => Future(None)
                  , post = ctx => request => Future(Right("result"))
                  ))
      } yield ()

      val (conf, dataStorage) = init(workflow)

      for {
        res1 <- post(conf, "step1")
        _ = assert(res1 == NotFound("stay"))

        res2 <- get(conf, "step2")
        _ = assert(res1 == NotFound("stay"))
      } yield succeed
  }
}
