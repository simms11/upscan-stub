package controllers

import java.io.File
import java.net.URL

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import model.{QuarantinedFile, Reference, UploadedFile}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{GivenWhenThen, Matchers}
import play.api.libs.Files.TemporaryFile
import play.api.mvc.{MultipartFormData, Result}
import play.api.test.FakeRequest
import services._
import uk.gov.hmrc.play.test.UnitSpec
import utils.Implicits.Base64StringOps

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.{Elem, XML}

class UploadControllerSpec extends UnitSpec with Matchers with GivenWhenThen with MockitoSugar {

  implicit val actorSystem: ActorSystem        = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val timeout: akka.util.Timeout      = 10.seconds

  "UploadController" should {
    "upload a successfully POSTed form and file" in {

      Given("a valid form containing a valid file")
      val testFile = new File("text-to-upload.txt")
      val filePart =
        new MultipartFormData.FilePart[TemporaryFile]("file", "text-to-upload.txt", None, new TemporaryFile(testFile))
      val formDataBody: MultipartFormData[TemporaryFile] = new MultipartFormData[TemporaryFile](
        dataParts = Map(
          "X-Amz-Algorithm"         -> Seq("some-algorithm"),
          "X-Amz-Credential"        -> Seq("some-credentials"),
          "X-Amz-Date"              -> Seq("some-date"),
          "policy"                  -> Seq("{\"policy\":null}".base64encode),
          "X-Amz-Signature"         -> Seq("some-signature"),
          "acl"                     -> Seq("some-acl"),
          "key"                     -> Seq("file-key"),
          "x-amz-meta-callback-url" -> Seq("http://mylocalservice.com/callback")
        ),
        files    = Seq(filePart),
        badParts = Nil
      )
      val uploadRequest = FakeRequest().withBody(formDataBody)

      val storedFile     = StoredFile(Array())
      val storageService = mock[FileStorageService]
      Mockito.when(storageService.get(any())).thenReturn(Some(storedFile))

      val notificationProcessor = mock[NotificationQueueProcessor]
      val virusScanner          = mock[VirusScanner]
      Mockito.when(virusScanner.checkIfClean(any())).thenReturn(Clean)

      val controller =
        new UploadController(storageService, notificationProcessor, virusScanner)(ExecutionContext.Implicits.global)

      When("upload is called")
      val uploadResult: Future[Result] = controller.upload()(uploadRequest)

      Then("the file should be saved to storage service")
      Mockito.verify(storageService).store(any(), any())

      And("the notification service should be called")
      Mockito
        .verify(notificationProcessor)
        .enqueueNotification(
          UploadedFile(
            new URL("http://mylocalservice.com/callback"),
            Reference("file-key"),
            new URL("http:/download/file-key")
          ))

      And("a No Content response should be returned")
      val uploadStatus = status(uploadResult)
      uploadStatus shouldBe 204
    }

    "store details of a file that fails virus scanning and return successful" in {

      Given("a valid form containing a valid file")
      val testFile = new File("text-to-upload.txt")
      val filePart =
        new MultipartFormData.FilePart[TemporaryFile]("file", "text-to-upload.txt", None, new TemporaryFile(testFile))
      val formDataBody: MultipartFormData[TemporaryFile] = new MultipartFormData[TemporaryFile](
        dataParts = Map(
          "X-Amz-Algorithm"         -> Seq("some-algorithm"),
          "X-Amz-Credential"        -> Seq("some-credentials"),
          "X-Amz-Date"              -> Seq("some-date"),
          "policy"                  -> Seq("{\"policy\":null}".base64encode),
          "X-Amz-Signature"         -> Seq("some-signature"),
          "acl"                     -> Seq("some-acl"),
          "key"                     -> Seq("file-key"),
          "x-amz-meta-callback-url" -> Seq("http://mylocalservice.com/callback")
        ),
        files    = Seq(filePart),
        badParts = Nil
      )
      val uploadRequest = FakeRequest().withBody(formDataBody)

      val storedFile     = StoredFile(Array())
      val storageService = mock[FileStorageService]
      Mockito.when(storageService.get(any())).thenReturn(Some(storedFile))

      val notificationProcessor = mock[NotificationQueueProcessor]
      val virusScanner          = mock[VirusScanner]
      Mockito.when(virusScanner.checkIfClean(any())).thenReturn(VirusFound("This test file failed scanning"))

      val controller =
        new UploadController(storageService, notificationProcessor, virusScanner)(ExecutionContext.Implicits.global)

      When("upload is called")
      val uploadResult: Future[Result] = controller.upload()(uploadRequest)

      Then("the file should be saved to storage service")
      Mockito.verify(storageService).store(any(), any())

      And("the notification service should be called")
      Mockito
        .verify(notificationProcessor)
        .enqueueNotification(
          QuarantinedFile(
            new URL("http://mylocalservice.com/callback"),
            Reference("file-key"),
            "This test file failed scanning"))

      And("a No Content response should be returned")
      val uploadStatus = status(uploadResult)
      uploadStatus shouldBe 204
    }

    "error on an incomplete POSTed form" in {

      Given("an invalid form containing a valid file")
      val temporaryFile = new TemporaryFile(new File("text-to-upload.txt"))
      val filePart =
        new MultipartFormData.FilePart[TemporaryFile]("file", "text-to-upload.txt", None, temporaryFile)
      val formDataBody: MultipartFormData[TemporaryFile] = new MultipartFormData[TemporaryFile](
        dataParts = Map(
          "X-Amz-Algorithm"         -> Seq("some-algorithm"),
          "X-Amz-Credential"        -> Seq("some-credentials"),
          "X-Amz-Date"              -> Seq("some-date"),
          "X-Amz-Signature"         -> Seq("some-signature"),
          "x-amz-meta-callback-url" -> Seq("http://mylocalservice.com/callback")
        ),
        files    = Seq(filePart),
        badParts = Nil
      )
      val uploadRequest = FakeRequest().withBody(formDataBody)

      val storageService        = mock[FileStorageService]
      val notificationProcessor = mock[NotificationQueueProcessor]
      val virusScanner          = mock[VirusScanner]

      val controller =
        new UploadController(storageService, notificationProcessor, virusScanner)(ExecutionContext.Implicits.global)

      When("upload is called")
      val uploadResult: Future[Result] = controller.upload()(uploadRequest)

      Then("a Bad Request response should be returned")
      val uploadStatus = status(uploadResult)
      uploadStatus shouldBe 400

      And("the body should contain XML detailing the error")
      val uploadBody: String    = bodyOf(uploadResult)
      val uploadBodyAsXml: Elem = xml.XML.loadString(uploadBody)

      (uploadBodyAsXml \\ "Error").nonEmpty      shouldBe true
      (uploadBodyAsXml \\ "Code").head.text      shouldBe "400"
      (uploadBodyAsXml \\ "Message").head.text   shouldBe "FormError(policy,List(error.required),List()), FormError(acl,List(error.required),List()), FormError(key,List(error.required),List())"
      (uploadBodyAsXml \\ "Resource").head.text  shouldBe "NoFileReference"
      (uploadBodyAsXml \\ "RequestId").head.text shouldBe "SomeRequestId"
    }

    "error when no file in POSTed form" in {

      Given("a valid form containing a NO file")
      val formDataBody: MultipartFormData[TemporaryFile] = new MultipartFormData[TemporaryFile](
        dataParts = Map(
          "X-Amz-Algorithm"         -> Seq("some-algorithm"),
          "X-Amz-Credential"        -> Seq("some-credentials"),
          "X-Amz-Date"              -> Seq("some-date"),
          "policy"                  -> Seq("{\"policy\":null}".base64encode),
          "X-Amz-Signature"         -> Seq("some-signature"),
          "acl"                     -> Seq("some-acl"),
          "key"                     -> Seq("file-key"),
          "x-amz-meta-callback-url" -> Seq("http://mylocalservice.com/callback")
        ),
        files    = Nil,
        badParts = Nil
      )
      val uploadRequest = FakeRequest().withBody(formDataBody)

      val storageService        = mock[FileStorageService]
      val notificationProcessor = mock[NotificationQueueProcessor]
      val virusScanner          = mock[VirusScanner]

      val controller =
        new UploadController(storageService, notificationProcessor, virusScanner)(ExecutionContext.Implicits.global)

      When("upload is called")
      val uploadResult: Future[Result] = controller.upload()(uploadRequest)

      Then("a Bad Request response should be returned")
      val uploadStatus = status(uploadResult)
      uploadStatus shouldBe 400

      And("the body should contain XML detailing the error")
      val uploadBody: String    = bodyOf(uploadResult)
      val uploadBodyAsXml: Elem = xml.XML.loadString(uploadBody)

      (uploadBodyAsXml \\ "Error").nonEmpty      shouldBe true
      (uploadBodyAsXml \\ "Code").head.text      shouldBe "400"
      (uploadBodyAsXml \\ "Message").head.text   shouldBe "'file' field not found"
      (uploadBodyAsXml \\ "Resource").head.text  shouldBe "NoFileReference"
      (uploadBodyAsXml \\ "RequestId").head.text shouldBe "SomeRequestId"
    }
  }
}