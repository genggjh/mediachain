package io.mediachain.translation

import java.io.File

import scala.io.Source
import cats.data.Xor
import io.mediachain.Types._
import org.json4s._
import io.mediachain.core.{Error, TranslationError}
import io.mediachain.BlobBundle
import io.mediachain.translation.JsonLoader.parseJArray
import org.json4s.jackson.Serialization.write
import com.fasterxml.jackson.core.JsonFactory
import io.mediachain.signatures.{PEMFileUtil, Signatory}

trait Implicit {
  implicit val factory = new JsonFactory
}
object `package` extends Implicit


trait Translator {
  val name: String
  val version: Int
  def translate(source: JObject): Xor[TranslationError, BlobBundle]
}

trait FSLoader[T <: Translator] {
  val translator: T

  val pairI: Iterator[Xor[TranslationError, (JObject, String)]]
  val path: String

  def signedBlobs(
    signatory: Signatory,
    bundle: BlobBundle,
    rawBlob: RawMetadataBlob): (BlobBundle, RawMetadataBlob) = {

    val signedBundle = bundle.withSignature(signatory)

    val signedRawBlob = rawBlob.withSignature(signatory)

    (signedBundle, signedRawBlob)
  }

  def loadBlobs(signatory: Option[Signatory] = None)
  : Iterator[Xor[TranslationError,(BlobBundle, RawMetadataBlob)]] = {
    pairI.map { pairXor =>
      pairXor.flatMap { case (json, raw) =>
        translator.translate(json).map { bundle: BlobBundle =>
          val rawBlob = RawMetadataBlob (None, raw)

          signatory
            .map(signedBlobs(_, bundle, rawBlob))
            .getOrElse((bundle, rawBlob))
        }
      }
    }
  }
}

trait DirectoryWalkerLoader[T <: Translator] extends FSLoader[T] {
  val fileI: Iterator[File] = DirectoryWalker.findWithExtension(new File(path), ".json")

  val (jsonI, rawI) = {
    val (left, right) = fileI.duplicate
    val jsonI = {
      left.map { file =>
        val obj = for {
          parser <- JsonLoader.createParser(file)
          obj <- JsonLoader.parseJOBject(parser)
        } yield obj

        obj.leftMap(err =>
          TranslationError.ParsingFailed(new RuntimeException(err + " at " + file.toString)))
      }
    }
    val rawI = right.map(Source.fromFile(_).mkString)

    (jsonI, rawI)
  }

  val pairI = jsonI.zip(rawI).map {
    case (jsonXor, raw) => jsonXor.map((_,raw))
    case _ => throw new RuntimeException("Should never get here")
  }
}

trait FlatFileLoader[T <: Translator] extends FSLoader[T] {
  val pairI = {
    implicit val formats = org.json4s.DefaultFormats

    JsonLoader.createParser(new File(path)) match {
      case err@Xor.Left(_) => Iterator(err)
      case Xor.Right(parser) => {
        parseJArray(parser).map {
          case Xor.Right(json: JObject) => Xor.right((json, write(json)))
          case err@(Xor.Left(_) | Xor.Right(_)) => Xor.left(TranslationError.ParsingFailed(new RuntimeException(err.toString)))
        }
      }
    }
  }
}

