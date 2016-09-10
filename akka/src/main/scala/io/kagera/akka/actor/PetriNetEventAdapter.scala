package io.kagera.akka.actor

import akka.actor.ActorSystem
import akka.serialization.SerializationExtension
import com.google.protobuf.ByteString
import io.kagera.akka.actor.PetriNetEventAdapter._
import io.kagera.akka.actor.PetriNetProcess.TransitionFired
import io.kagera.akka.persistence.{ ConsumedToken, ProducedToken, SerializedData }
import io.kagera.api._
import io.kagera.api.colored.{ ColoredMarking, _ }

import scala.runtime.BoxedUnit

object PetriNetEventAdapter {

  // this approach is fragile, the function cannot change ever or recovery breaks
  // a more robust alternative is to generate the ids and persist them
  def tokenIdentifier[C](p: Place[C]): Any ⇒ Int = obj ⇒ hashCodeOf[Any](obj)

  def hashCodeOf[T](e: T): Int = {
    if (e == null)
      -1
    else
      e.hashCode()
  }
}

trait PetriNetEventAdapter[S] {

  def system: ActorSystem

  private lazy val serialization = SerializationExtension.get(system)

  def serializeObject(obj: AnyRef): Option[SerializedData] = {
    // no need to serialize unit
    if (obj.isInstanceOf[Unit]) {
      None
    } else {
      // for now we re-use akka Serialization extension for pluggable serializers
      val serializer = serialization.findSerializerFor(obj)
      val bytes = serializer.toBinary(obj)

      // we should not have to copy the bytes
      Some(SerializedData(
        serializerId = Some(serializer.identifier),
        manifest = None,
        data = Some(ByteString.copyFrom(bytes))
      ))
    }
  }

  def deserializeObject(obj: Option[SerializedData]): AnyRef = {

    obj.map {
      case SerializedData(None, _, Some(data)) ⇒
        throw new IllegalStateException(s"Missing serializer id")
      case SerializedData(Some(serializerId), _, Some(data)) ⇒
        val serializer = serialization.serializerByIdentity.getOrElse(serializerId,
          throw new IllegalStateException(s"No serializer found with id $serializerId")
        )
        serializer.fromBinary(data.toByteArray)
    }.getOrElse(BoxedUnit.UNIT)
  }

  def writeEvent(e: TransitionFired): io.kagera.akka.persistence.TransitionFired = {

    val consumedTokens: Seq[ConsumedToken] = e.consumed.data.toSeq.flatMap {
      case (place, tokens) ⇒ tokens.toSeq.map {
        case (value, count) ⇒ ConsumedToken(
          placeId = Some(place.id.toInt),
          tokenId = Some(tokenIdentifier(place)(value)),
          count = Some(count)
        )
      }
    }

    val producedTokens: Seq[ProducedToken] = e.produced.data.toSeq.flatMap {
      case (place, tokens) ⇒ tokens.toSeq.map {
        case (value, count) ⇒ ProducedToken(
          placeId = Some(place.id.toInt),
          tokenId = Some(tokenIdentifier(place)(value)),
          count = Some(count),
          tokenData = serializeObject(value.asInstanceOf[AnyRef])
        )
      }
    }

    val protobufEvent = io.kagera.akka.persistence.TransitionFired(
      transitionId = Some(e.transition_id),
      timeStarted = Some(e.time_started),
      timeCompleted = Some(e.time_completed),
      consumed = consumedTokens,
      produced = producedTokens,
      data = serializeObject(e.out.asInstanceOf[AnyRef])
    )

    protobufEvent
  }

  def readEvent(process: ExecutablePetriNet[S], currentMarking: ColoredMarking, e: io.kagera.akka.persistence.TransitionFired): TransitionFired = {

    val transition = process.getTransitionById(e.transitionId.get)

    val consumed = e.consumed.foldLeft(ColoredMarking.empty) {
      case (accumulated, ConsumedToken(Some(placeId), Some(tokenId), Some(count))) ⇒
        val place = currentMarking.markedPlaces.getById(placeId)
        val value = currentMarking(place).keySet.find(e ⇒ tokenIdentifier(place)(e) == tokenId).get
        accumulated.add(place.asInstanceOf[Place[Any]], value, count)
      case _ ⇒ throw new IllegalStateException("Missing data in persisted ConsumedToken")
    }

    val produced = e.produced.foldLeft(ColoredMarking.empty) {
      case (accumulated, ProducedToken(Some(placeId), Some(tokenId), Some(count), data)) ⇒
        val place = process.places.getById(placeId)
        val value = deserializeObject(data)
        accumulated.add(place.asInstanceOf[Place[Any]], value, count)
      case _ ⇒ throw new IllegalStateException("Missing data in persisted ProducedToken")
    }

    val data = deserializeObject(e.data)

    val timeStarted = e.timeStarted.getOrElse(throw new IllegalStateException("Missing field timeStarted"))

    val timeCompleted = e.timeCompleted.getOrElse(throw new IllegalStateException("Missing field timeCompleted"))

    TransitionFired(transition, timeStarted, timeCompleted, consumed, produced, data)
  }
}
