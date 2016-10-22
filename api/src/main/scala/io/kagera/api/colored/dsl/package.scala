package io.kagera.api.colored

import fs2.Task
import io.kagera.api._
import io.kagera.api.multiset._

import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }
import scalax.collection.Graph
import scalax.collection.edge.WLDiEdge

package object dsl {

  implicit class TransitionDSL(t: Transition[_, _, _]) {
    def ~>[C](p: Place[C], weight: Long = 1): Arc = arc(t, p, weight)
  }

  implicit class PlaceDSL[C](p: Place[C]) {
    def ~>(t: Transition[C, _, _], weight: Long = 1, filter: C ⇒ Boolean = token ⇒ true): Arc = arc[C](p, t, weight, filter)
  }

  def arc(t: Transition[_, _, _], p: Place[_], weight: Long): Arc = WLDiEdge[Node, String](Right(t), Left(p))(weight, "")

  def arc[C](p: Place[C], t: Transition[_, _, _], weight: Long, filter: C ⇒ Boolean = (token: C) ⇒ true): Arc = {
    val innerEdge = new PTEdgeImpl[C](weight, filter)
    WLDiEdge[Node, PTEdge[C]](Left(p), Right(t))(weight, innerEdge)
  }

  def constantTransition[I, O, S](id: Long, label: String, isManaged: Boolean = false, constant: O) =
    new AbstractTransition[I, O, S](id, label, isManaged, Duration.Undefined) {

      override val toString = label

      override def apply(inAdjacent: MultiSet[Place[_]], outAdjacent: MultiSet[Place[_]]) =
        (marking, state, input) ⇒ Task.delay {
          val produced = outAdjacent.map {
            case (place, weight) ⇒ place -> produceTokens(place, weight.toInt)
          }.toMarking

          produced -> constant
        }

      def produceTokens[C](place: Place[C], count: Int): MultiSet[C] = MultiSet.empty[C] + (constant.asInstanceOf[C] -> count)

      override def updateState = s ⇒ e ⇒ s
    }

  def nullTransition[S](id: Long, label: String, automated: Boolean = false) = constantTransition[Unit, Unit, S](id, label, automated, ())

  def process[S](params: Arc*): ExecutablePetriNet[S] = {
    val petriNet = new ScalaGraphPetriNet(Graph(params: _*)) with ColoredTokenGame with TransitionExecutor[S]

    requireUniqueElements(petriNet.places.toSeq.map(_.id), "Place identifier")
    requireUniqueElements(petriNet.transitions.toSeq.map(_.id), "Transition identifier")

    petriNet
  }
}
