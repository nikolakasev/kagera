package io.process.statebox.process

import akka.actor.Props
import akka.util.Timeout
import io.process.statebox.ServicesImpl
import io.process.statebox.actor.{ PetriNetActor, PetriNetDebugging }
import PetriNetDebugging.Step
import io.process.statebox.process.colored._

import scala.concurrent.Await

object Test extends App with ServicesImpl {

  val a = ColoredPlace[Int]("a")
  val b = ColoredPlace[Int]("b")
  val c = ColoredPlace[Int]("result")

  def init() = (5, 5)
  def sum(a: Int, b: Int) = a + b

  val sumT = toTransition2("sum", sum)
  val initT = toTransition0("init", init)

  val simpleProcess = process(
    initT ~> %(a, b),
    %(a, b) ~> sumT,
    sumT ~> c)

  val actor = system.actorOf(Props(new PetriNetActor(simpleProcess)))

  import akka.pattern.ask
  import scala.concurrent.duration._

  implicit val timeout = Timeout(2 seconds)

  actor ? Step

  Await.result(actor ? Step, timeout.duration)
}
