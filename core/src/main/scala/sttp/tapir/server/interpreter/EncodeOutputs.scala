package sttp.tapir.server.interpreter

import sttp.model._
import sttp.tapir.EndpointOutput.StatusMapping
import sttp.tapir.internal.{Params, ParamsAsAny, SplitParams, _}
import sttp.tapir.model.ServerRequest
import sttp.tapir.{Codec, CodecFormat, EndpointIO, EndpointOutput, Mapping, StreamBodyIO, WebSocketBodyOutput}

import java.nio.charset.Charset
import scala.collection.immutable.Seq

class EncodeOutputs[B, S](rawToResponseBody: ToResponseBody[B, S], request: ServerRequest) {
  def apply(output: EndpointOutput[_], value: Params, ov: OutputValues[B]): OutputValues[B] = {
    output match {
      case s: EndpointIO.Single[_]                    => applySingle(s, value, ov)
      case s: EndpointOutput.Single[_]                => applySingle(s, value, ov)
      case EndpointIO.Pair(left, right, _, split)     => applyPair(left, right, split, value, ov)
      case EndpointOutput.Pair(left, right, _, split) => applyPair(left, right, split, value, ov)
      case EndpointOutput.Void()                      => throw new IllegalArgumentException("Cannot encode a void output!")
    }
  }

  private def applyPair(
      left: EndpointOutput[_],
      right: EndpointOutput[_],
      split: SplitParams,
      params: Params,
      ov: OutputValues[B]
  ): OutputValues[B] = {
    val (leftParams, rightParams) = split(params)
    apply(right, rightParams, apply(left, leftParams, ov))
  }

  private def applySingle(output: EndpointOutput.Single[_], value: Params, ov: OutputValues[B]): OutputValues[B] = {
    def encodedC[T](codec: Codec[_, _, _ <: CodecFormat]): T = codec.asInstanceOf[Codec[T, Any, CodecFormat]].encode(value.asAny)
    def encodedM[T](mapping: Mapping[_, _]): T = mapping.asInstanceOf[Mapping[T, Any]].encode(value.asAny)
    output match {
      case EndpointIO.Empty(_, _)                   => ov
      case EndpointOutput.FixedStatusCode(sc, _, _) => ov.withStatusCode(sc)
      case EndpointIO.FixedHeader(header, _, _)     => ov.withHeader(header.name, header.value)
      case EndpointIO.Body(rawBodyType, codec, _) =>
        val maybeCharset = if (codec.format.mediaType.mainType.equalsIgnoreCase("text")) charset(rawBodyType) else None
        ov.withBody(headers => rawToResponseBody.fromRawValue(encodedC[Any](codec), headers, codec.format, rawBodyType))
          .withDefaultContentType(codec.format, maybeCharset)
      case EndpointIO.StreamBodyWrapper(StreamBodyIO(_, codec, _, charset)) =>
        ov.withBody(headers => rawToResponseBody.fromStreamValue(encodedC(codec), headers, codec.format, charset))
          .withDefaultContentType(codec.format, charset)
          .withHeaderTransformation(hs =>
            if (hs.exists(_.is(HeaderNames.ContentLength))) hs else hs :+ Header(HeaderNames.TransferEncoding, "chunked")
          )
      case EndpointIO.Header(name, codec, _) =>
        encodedC[List[String]](codec).foldLeft(ov) { case (ovv, headerValue) => ovv.withHeader(name, headerValue) }
      case EndpointIO.Headers(codec, _) =>
        encodedC[List[sttp.model.Header]](codec).foldLeft(ov)((ov2, h) => ov2.withHeader(h.name, h.value))
      case EndpointIO.MappedPair(wrapped, mapping) => apply(wrapped, ParamsAsAny(encodedM[Any](mapping)), ov)
      case EndpointOutput.StatusCode(_, codec, _)  => ov.withStatusCode(encodedC[StatusCode](codec))
      case EndpointOutput.WebSocketBodyWrapper(o) =>
        ov.withBody(_ =>
          rawToResponseBody.fromWebSocketPipe(
            encodedC[rawToResponseBody.streams.Pipe[Any, Any]](o.codec),
            o.asInstanceOf[WebSocketBodyOutput[rawToResponseBody.streams.Pipe[Any, Any], Any, Any, Any, S]]
          )
        )
      case EndpointOutput.OneOf(mappings, mapping) =>
        val enc = encodedM[Any](mapping)

        val bodyMappings: Map[MediaType, StatusMapping[_]] = mappings
          .filter(_.appliesTo(enc))
          .flatMap(sm =>
            sm.output.traverseOutputs {
              case EndpointIO.Body(bodyType, codec, _) =>
                Vector[(MediaType, StatusMapping[_])](
                  charset(bodyType).map(ch => codec.format.mediaType.charset(ch.name())).getOrElse(codec.format.mediaType) -> sm
                )
              case EndpointIO.StreamBodyWrapper(StreamBodyIO(_, codec, _, charset)) =>
                Vector[(MediaType, StatusMapping[_])](
                  charset.map(ch => codec.format.mediaType.charset(ch.name())).getOrElse(codec.format.mediaType) -> sm
                )
            }
          )
          .toMap

        if (bodyMappings.nonEmpty) {
          val mediaTypes = bodyMappings.keys.toVector
          MediaType
            .bestMatch(mediaTypes, request.ranges.getOrElse(Seq.empty[ContentTypeRange]))
            .flatMap(mt => bodyMappings.get(mt).orElse(bodyMappings.headOption.map { case (_, sm) => sm }))
            .map(sm => apply(sm.output, ParamsAsAny(enc), sm.statusCode.map(ov.withStatusCode).getOrElse(ov)))
            .getOrElse(throw new IllegalStateException(s"No mapping for requested content type"))
        } else {
          val mapping = mappings
            .find(_.appliesTo(enc))
            .getOrElse(throw new IllegalArgumentException(s"No status code mapping for value: $enc, in output: $output"))
          apply(mapping.output, ParamsAsAny(enc), mapping.statusCode.map(ov.withStatusCode).getOrElse(ov))
        }
      case EndpointOutput.MappedPair(wrapped, mapping) => apply(wrapped, ParamsAsAny(encodedM[Any](mapping)), ov)
    }
  }
}

case class OutputValues[B](
    body: Option[HasHeaders => B],
    baseHeaders: Vector[Header],
    headerTransformations: Vector[Vector[Header] => Vector[Header]],
    statusCode: Option[StatusCode]
) {
  def withBody(b: HasHeaders => B): OutputValues[B] = {
    if (body.isDefined) {
      throw new IllegalArgumentException("Body is already defined")
    }

    copy(body = Some(b))
  }

  def withHeaderTransformation(t: Vector[Header] => Vector[Header]): OutputValues[B] =
    copy(headerTransformations = headerTransformations :+ t)
  def withDefaultContentType(format: CodecFormat, charset: Option[Charset]): OutputValues[B] = {
    withHeaderTransformation { hs =>
      if (hs.exists(_.is(HeaderNames.ContentType))) hs
      else hs :+ Header(HeaderNames.ContentType, charset.fold(format.mediaType)(format.mediaType.charset(_)).toString())
    }
  }

  def withHeader(n: String, v: String): OutputValues[B] = copy(baseHeaders = baseHeaders :+ Header(n, v))

  def withStatusCode(sc: StatusCode): OutputValues[B] = copy(statusCode = Some(sc))

  def headers: Seq[Header] = {
    headerTransformations.foldLeft(baseHeaders) { case (hs, t) => t(hs) }
  }
}
object OutputValues {
  def empty[B]: OutputValues[B] = OutputValues[B](None, Vector.empty, Vector.empty, None)
}
