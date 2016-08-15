/*
 * Copyright 2016 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.http.cache.client

import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{Matchers, WordSpecLike}
import org.scalatest.concurrent.ScalaFutures
import play.api.Logger
import play.api.libs.json.{JsValue, Json, Reads, Writes}
import uk.gov.hmrc.crypto._
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.http.{HttpDelete, HttpPut, HttpGet}

import scala.collection.mutable
import scala.concurrent.Future


class ShortLivedCacheSpec extends WordSpecLike with Matchers with ScalaFutures {

  implicit val hc = HeaderCarrier()
  implicit val defaultPatience = PatienceConfig(timeout = Span(5, Seconds), interval = Span(100, Millis))

  "ShortLivedCacheWithCrpto" should {
    import uk.gov.hmrc.http.cache.client.FormOnPage3.formats

    val formOnPage = FormOnPage3("abdu", field2 = false)

    val slcCrypto = new ShortLivedCache {
      val shortLiveCache: ShortLivedHttpCaching = TestShortLiveHttpCaching
      val crypto = TestCrypto
    }

    "encrypt and cache a given data" in {
      val cm = slcCrypto.cache("save4later", "form1", FormOnPage3("me", true))(hc, FormOnPage3.formats).futureValue
      val cm2 = slcCrypto.fetch("save4later").futureValue
      cm2 should be(Some(cm))
    }

    "encrypt the data and fetch by key" in {
      slcCrypto.cache("save4later", "form2", formOnPage).futureValue
      val form = slcCrypto.fetchAndGetEntry[FormOnPage3]("save4later", "form2")(hc, FormOnPage3.formats).futureValue
      form should be(Some(formOnPage))
    }

    "return decrypted cache entry" in {
      val cm = slcCrypto.cache("save4later", "form3", formOnPage).futureValue
      val form = cm.getEntry[FormOnPage3]("form3")
      form should be(Some(formOnPage))
    }

    "not return any uncached entry when the key is missing" in {
      val cm = slcCrypto.cache("save4later", "form4", formOnPage).futureValue
      val form = cm.getEntry[FormOnPage3]("form6")
      form should be(empty)
    }

    "not return any uncached entry when fetched" in {
      slcCrypto.cache("save4later", "form5", formOnPage).futureValue
      val form = slcCrypto.fetchAndGetEntry[FormOnPage3]("save4later", "form7")(hc, FormOnPage3.formats).futureValue
      form should be(empty)
    }

    "fetch non existing cache" in {
      val emptyCache = slcCrypto.fetch("non-existing-item").futureValue
      emptyCache should be(empty)
    }

    "capture crypto exception during fetchAndGetEntry" in {
      intercept[CachingException] {
        slcCrypto.fetchAndGetEntry("save4later", "exception-fetch-id")(hc, FormOnPage3.formats)
      }
    }

    "capture crypto exception during getEntry" in {
      intercept[CachingException] {
        slcCrypto.cache("exception-cache-id", "exception-key", formOnPage).futureValue
        val cm = slcCrypto.fetch("exception-cache-id").futureValue
        cm.map(f => f.getEntry("exception-key"))
      }
    }

  }
}

object FormOnPage3 {
  implicit val formats = Json.format[FormOnPage3]
}

case class FormOnPage3(field1: String, field2: Boolean)


object TestCrypto extends CompositeSymmetricCrypto {

  override def encrypt(value: PlainContent): Crypted = value match {
    case PlainText(text) => Crypted(text)
    case _ => throw new RuntimeException(s"Unable to encrypt unknown message type: $value")
  }

  override def decrypt(crypted: Crypted): PlainText = {
    PlainText(crypted.value)
  }

  override protected lazy val currentCrypto: Encrypter with Decrypter = ???
  override protected lazy val previousCryptos: Seq[Decrypter] = ???
}

class TestCacheMap(override val id: String, override val data: Map[String, JsValue]) extends CacheMap(id, data) {
  override def getEntry[T](key: String)(implicit fjs: Reads[T]): Option[T] =
    if (key == "exception-key") throw new SecurityException("Couldn't decrypt entry")
    else
      super.getEntry(key)

}

object TestShortLiveHttpCaching extends ShortLivedHttpCaching {

  import scala.concurrent.ExecutionContext.Implicits.global

  override lazy val defaultSource: String = "save4later"
  override lazy val baseUri: String = "save4later"
  override lazy val domain: String = "save4later"
  val map = mutable.HashMap[String, CacheMap]()

  override def cache[A](cacheId: String, formId: String, body: A)(implicit hc: HeaderCarrier, wts: Writes[A]): Future[CacheMap] = {
    Future {
      val jsValue = wts.writes(body)
      val cacheMap = new TestCacheMap(cacheId, Map(formId -> jsValue))
      map.put(cacheId, cacheMap)
      cacheMap
    }
  }

  override def fetch(cacheId: String)(implicit hc: HeaderCarrier): Future[Option[CacheMap]] = {
    val om = map.get(cacheId)
    Future.successful(om)
  }

  override def fetchAndGetEntry[T](cacheId: String, key: String)(implicit hc: HeaderCarrier, rds: Reads[T]): Future[Option[T]] =
    if (key == "exception-fetch-id") {
      throw new SecurityException("Unable to decrypt entry")
    } else
      Future {
        val cm = map.get(cacheId)
        cm.flatMap { cm =>
          val e = cm.getEntry[T](key)
          Logger.info(s"Fetching entry:$e from cache by key:$key with reader:$rds")
          e
        }
      }

  override def http: HttpGet with HttpPut with HttpDelete = ???
}
