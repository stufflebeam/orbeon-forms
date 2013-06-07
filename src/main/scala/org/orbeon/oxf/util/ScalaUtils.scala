/**
 *  Copyright (C) 2011 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.util

import org.orbeon.errorified.Exceptions
import org.orbeon.exception._
import org.apache.log4j.Logger
import java.net.URLEncoder.{encode ⇒ encodeURL}
import java.net.URLDecoder.{decode ⇒ decodeURL}
import org.apache.commons.lang3.StringUtils.{isNotBlank, trimToEmpty}
import collection.mutable
import collection.generic.CanBuildFrom
import reflect.ClassTag
import scala.util.{Success, Failure, Try}
import scala.util.control.NonFatal

object ScalaUtils {

    private val CopyBufferSize = 8192

    type Readable[T] = {
        def close()
        def read(b: Array[T]): Int
    }

    type Writable[T] = {
        def close()
        def write(cbuf: Array[T], off: Int, len: Int)
        def flush()
    }

    // Copy a stream, with optional progress callback
    // This fails at runtime due to: https://lampsvn.epfl.ch/trac/scala/ticket/2672
    def genericCopyStream[T : ClassTag](in: Readable[T], out: Writable[T], progress: Long ⇒ Unit = _ ⇒ ()) = {

        require(in ne null)
        require(out ne null)

        useAndClose(in) { in ⇒
            useAndClose(out) { out ⇒
                val buffer = new Array[T](CopyBufferSize)
                Iterator continually (in read buffer) takeWhile (_ != -1) filter (_ > 0) foreach { read ⇒
                    progress(read)
                    out.write(buffer, 0, read)
                }
            }
        }
    }

    def copyStream(in: Readable[Byte], out: Writable[Byte], progress: Long ⇒ Unit = _ ⇒ ()) = {

        require(in ne null)
        require(out ne null)

        useAndClose(in) { in ⇒
            useAndClose(out) { out ⇒
                val buffer = new Array[Byte](CopyBufferSize)
                Iterator continually (in read buffer) takeWhile (_ != -1) filter (_ > 0) foreach { read ⇒
                    progress(read)
                    out.write(buffer, 0, read)
                }
                out.flush()
            }
        }
    }

    def copyReader(in: Readable[Char], out: Writable[Char], progress: Long ⇒ Unit = _ ⇒ ()) = {

        require(in ne null)
        require(out ne null)

        useAndClose(in) { in ⇒
            useAndClose(out) { out ⇒
                val buffer = new Array[Char](CopyBufferSize)
                Iterator continually (in read buffer) takeWhile (_ != -1) filter (_ > 0) foreach { read ⇒
                    progress(read)
                    out.write(buffer, 0, read)
                }
            }
        }
    }

    // Use a closable item and make sure an attempt to close it is done after use
    def useAndClose[T <: {def close()}, U](closable: T)(block: T ⇒ U): U =
        try block(closable)
        finally {
            if (closable ne null)
                runQuietly(closable.close())
        }

    def connectAndDisconnect[T <: {def connect(); def disconnect()}, U](connection: T)(block: ⇒ U): U = {
        connection.connect()
        try block
        finally {
            runQuietly(connection.disconnect())
        }
    }

    // Run a block and swallow any exception. Use only for things like close().
    def runQuietly(block: ⇒ Unit) =
        try block
        catch {
            case NonFatal(_) ⇒ // NOP
        }

    // Run the body and log/rethrow the root cause of any Exception caught
    def withRootException[T](action: String, newException: Throwable ⇒ Exception)(body: ⇒ T)(implicit logger: Logger): T =
        try body
        catch {
            case NonFatal(t) ⇒
                logger.error("Exception when running " + action + '\n' + OrbeonFormatter.format(t))
                throw newException(Exceptions.getRootThrowable(t))
        }

    def dropTrailingSlash(s: String) = if (s.size == 0 || s.last != '/') s else s.init
    def dropStartingSlash(s: String) = if (s.size == 0 || s.head != '/') s else s.tail
    def appendStartingSlash(s: String) = if (s.size != 0 && s.head == '/') s else '/' + s

    // Semi-standard pipe operator
    implicit class PipeOps[A](val a: A) extends AnyVal { def |>[B](f: A ⇒ B) = f(a) }

    // Convert a string of tokens to a set
    // NOTE: ("" split """\s+""" toSet).size == 1!
    def stringToSet(s: String)               = split[Set](s)
    def stringOptionToSet(s: Option[String]) = s map stringToSet getOrElse Set.empty[String]

    // Split out a URL's query part
    def splitQuery(url: String): (String, Option[String]) = {
        val index = url.indexOf('?')
        if (index == -1)
            (url, None)
        else
            (url.substring(0, index), Some(url.substring(index + 1)))
    }

    // Recombine a path/query and parameters into a resulting URL
    def recombineQuery(pathQuery: String, params: Seq[(String, String)]) =
        pathQuery + (if (params.isEmpty) "" else ((if (pathQuery.contains("?")) "&" else "?") + encodeSimpleQuery(params)))

    // Decode a query string into a sequence of pairs
    // We assume that there are no spaces in the input query
    def decodeSimpleQuery(query: String): Seq[(String, String)] =
        for {
            nameValue      ← query.split('&').toList
            if nameValue.nonEmpty
            nameValueArray = nameValue.split('=')
            if nameValueArray.size >= 1
            encodedName    = nameValueArray(0)
            if encodedName.nonEmpty
            decodedName    = decodeURL(encodedName, "utf-8")
            decodedValue   = decodeURL(nameValueArray.lift(1) getOrElse "", "utf-8")
        } yield
            decodedName → decodedValue

    // Get the first query parameter value for the given name
    def getFirstQueryParameter(url: String, name: String) = {
        val query = splitQuery(url)._2 map decodeSimpleQuery getOrElse Seq()

        query find (_._1 == name) map { case (k, v) ⇒ v }
    }

    // Encode a sequence of pairs to a query string
    def encodeSimpleQuery(parameters: Seq[(String, String)]): String =
        parameters map { case (name, value) ⇒ encodeURL(name, "utf-8") + '=' + encodeURL(value, "utf-8") } mkString "&"

    // Combine the second values of each tuple that have the same name
    // The caller can specify the type of the resulting values, e.g.:
    // - combineValues[AnyRef, Array]
    // - combineValues[String, List]
    def combineValues[U, T[_]](parameters: Seq[(String, U)])(implicit cbf: CanBuildFrom[Nothing, U, T[U]]): Seq[(String, T[U])] = {
        val result = mutable.LinkedHashMap[String, mutable.Builder[U, T[U]]]()

        for ((name, value) ← parameters)
            result.getOrElseUpdate(name, cbf()) += value

        result map { case (k, v) ⇒ k → v.result } toList
    }

    // If the string is null or empty, return None, otherwise return Some(trimmed value)
    def nonEmptyOrNone(s: String) = Option(s) map trimToEmpty filter isNotBlank

    // Extensions on Boolean
    implicit class BooleanWrapper(val b: Boolean) extends AnyVal {
        def option[A](a: ⇒ A)   = if (b) Option(a)   else None
        def list[A](a: ⇒ A)     = if (b) List(a)     else Nil
        def set[A](a: ⇒ A)      = if (b) Set(a)      else Set.empty[A]
        def iterator[A](a: ⇒ A) = if (b) Iterator(a) else Iterator.empty
    }

    // Extensions on Iterator[T]
    implicit class IteratorWrapper[T](val i: Iterator[T]) extends AnyVal {
        def headOption = i.hasNext option (i.next())
    }

    // WARNING: Remember that type erasure takes place! collectByErasedType[T[U1]] will work even if the underlying type was T[U2]!
    // NOTE: `case t: T` works with `ClassTag` only since Scala 2.10.
    def collectByErasedType[T: ClassTag](value: AnyRef): Option[T] = Option(value) collect { case t: T ⇒ t }

    /*
     * Rewrite in Scala of Apache StringUtils.splitWorker (http://www.apache.org/licenses/LICENSE-2.0).
     *
     * This implementation can return any collection type for which there is a builder:
     *
     * split[List]("a b")
     * split[Array]("a b")
     * split[Set]("a b")
     * split[LinkedHashSet]("a b")
     * split("a b")
     *
     * Or:
     *
     * val result: List[String]          = split("a b")(breakOut)
     * val result: Array[String]         = split("a b")(breakOut)
     * val result: Set[String]           = split("a b")(breakOut)
     * val result: LinkedHashSet[String] = split("a b")(breakOut)
     */
    def split[T[_]](str: String, sep: String = null, max: Int = 0)(implicit cbf: CanBuildFrom[Nothing, String, T[String]]): T[String] = {

        val builder = cbf()

        if (str ne null) {
            val len = str.length
            if (len != 0) {
                var sizePlus1 = 1
                var i = 0
                var start = 0
                var doMatch = false

                val test: Char ⇒ Boolean =
                    if (sep eq null)
                        Character.isWhitespace
                    else if (sep.length == 1) {
                        val sepChar = sep.charAt(0)
                        sepChar ==
                    } else
                        sep.indexOf(_) >= 0

                while (i < len) {
                    if (test(str.charAt(i))) {
                        if (doMatch) {
                            if (sizePlus1 == max)
                                i = len

                            sizePlus1 += 1

                            builder += str.substring(start, i)
                            doMatch = false
                        }
                        i += 1
                        start = i
                    } else {
                        doMatch = true
                        i += 1
                    }
                }

                if (doMatch)
                    builder += str.substring(start, i)
            }
        }
        builder.result()
    }

    private class OnFailurePF[U](f: PartialFunction[Throwable, Any]) extends PartialFunction[Throwable, Try[U]] {
        def isDefinedAt(x: Throwable) = f.isDefinedAt(x)
        def apply(v1: Throwable) = {
            f.apply(v1)
            Failure(v1)
        }
    }

    private val RootThrowablePF: PartialFunction[Throwable, Try[Nothing]] = { case NonFatal(t) ⇒ Failure(Exceptions.getRootThrowable(t)) }

    // Operations on Try
    implicit class TryOps[U](val t: Try[U]) extends AnyVal {
        def onFailure(f: PartialFunction[Throwable, Any]) =
            t recoverWith new OnFailurePF(f)

        def rootFailure: Try[U] = {
            if (t.isFailure)
                t recoverWith RootThrowablePF
            else
                t
        }

        def doEitherWay(f: ⇒ Any): Try[U] =
            try t match {
                case result @ Success(_) ⇒ f; result
                case result @ Failure(_) ⇒ f; result
            } catch {
                case NonFatal(e) ⇒ Failure(e)
            }

        def iterator: Iterator[U] = t.toOption.iterator
    }
}