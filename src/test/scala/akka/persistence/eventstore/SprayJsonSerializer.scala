package akka.persistence.eventstore

import java.nio.ByteBuffer
import java.nio.charset.Charset

import akka.actor.ExtendedActorSystem
import akka.persistence.eventstore.snapshot.EventStoreSnapshotStore.SnapshotEvent
import akka.persistence.eventstore.snapshot.EventStoreSnapshotStore.SnapshotEvent.Snapshot
import akka.persistence.{ PersistentRepr, SnapshotMetadata }
import akka.util.ByteString
import eventstore.{ Content, ContentType, Event, EventData }
import spray.json._

import scala.reflect.ClassTag

class SprayJsonSerializer(val system: ExtendedActorSystem) extends EventStoreSerializer {
  import SprayJsonSerializer._

  val protocol = new JsonProtocol(system)
  import protocol._

  def identifier = Identifier

  def includeManifest = true

  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]) = {
    def fromBinary(manifest: Class[_]) = {
      val json = new String(bytes, UTF8).parseJson
      val format = classFormat(manifest)
      format.read(json)
    }
    fromBinary(manifest getOrElse sys.error("manifest is missing"))
  }

  def toBinary(x: AnyRef) = {
    val json = classFormat(classFor(x)).write(x)
    val str = json.compactPrint
    str.getBytes(UTF8)
  }

  def toEvent(x: AnyRef) = x match {
    case x: PersistentRepr => EventData(
      eventType = classFor(x).getName,
      data = Content(ByteString(toBinary(x)), ContentType.Json))

    case x: SnapshotEvent => EventData(
      eventType = classFor(x).getName,
      data = Content(ByteString(toBinary(x)), ContentType.Json))

    case _ => sys.error(s"Cannot serialize $x, SnapshotEvent expected")
  }

  def fromEvent(event: Event, manifest: Class[_]) = {
    val clazz = Class.forName(event.data.eventType)
    val result = fromBinary(event.data.data.value.toArray, clazz)
    if (manifest.isInstance(result)) result
    else sys.error(s"Cannot deserialize event as $manifest, event: $event")
  }

  def classFor(x: AnyRef) = x match {
    case x: PersistentRepr => classOf[PersistentRepr]
    case _                 => x.getClass
  }
}

object SprayJsonSerializer {
  val UTF8: Charset = Charset.forName("UTF-8")
  val Identifier: Int = ByteBuffer.wrap("spray-json".getBytes(UTF8)).getInt

  class JsonProtocol(system: ExtendedActorSystem) extends DefaultJsonProtocol {
    val SnapshotMetadataFormat = jsonFormat3(SnapshotMetadata.apply)
    val ClassFormat = Map(
      entry(jsonFormat3(SnapshotMetadata.apply)),
      entry(jsonFormat4(SnapshotEvent.DeleteCriteria.apply)),
      entry(jsonFormat2(SnapshotEvent.Delete.apply)),
      entry(SnapshotFormat),
      entry(PersistenceReprFormat))

    def classFormat[T](x: Class[T]) = ClassFormat.getOrElse(x, sys.error(s"JsonFormat not found for $x"))

    def entry[T](format: JsonFormat[T])(implicit tag: ClassTag[T]): (Class[_], JsonFormat[AnyRef]) = {
      tag.runtimeClass -> format.asInstanceOf[JsonFormat[AnyRef]]
    }

    object SnapshotFormat extends JsonFormat[Snapshot] {
      def read(json: JsValue) = json.asJsObject.getFields("data", "metadata") match {
        case Seq(JsString(data), metadata) => Snapshot(data, SnapshotMetadataFormat.read(metadata))
        case _                             => deserializationError("string expected")
      }

      def write(x: Snapshot) = x.data match {
        case data: String => JsObject("data" -> JsString(data), "metadata" -> SnapshotMetadataFormat.write(x.metadata))
        case _            => serializationError("string expected")
      }
    }

    object PersistenceReprFormat extends JsonFormat[PersistentRepr] {

      val format = jsonFormat6(Mapping.apply)

      def read(json: JsValue) = {
        val x = format.read(json)
        PersistentRepr(
          payload = x.payload,
          sequenceNr = x.sequenceNr,
          persistenceId = x.persistenceId,
          manifest = x.manifest,
          sender = system.provider.resolveActorRef(x.sender),
          writerUuid = x.writerUuid)
      }

      def write(x: PersistentRepr) = {
        val mapping = Mapping(
          payload = x.payload.asInstanceOf[String],
          sequenceNr = x.sequenceNr,
          persistenceId = x.persistenceId,
          manifest = x.manifest,
          sender = x.sender.path.toSerializationFormat,
          writerUuid = x.writerUuid)
        format.write(mapping)
      }

      case class Mapping(
        payload: String,
        sequenceNr: Long,
        persistenceId: String,
        manifest: String,
        sender: String,
        writerUuid: String)
    }
  }
}