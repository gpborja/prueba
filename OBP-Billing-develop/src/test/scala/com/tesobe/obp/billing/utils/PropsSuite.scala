package com.tesobe.obp.billing.utils

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class PropsSuite extends munit.FunSuite {

  test("loads the file named by the system property and expands placeholders") {
    val file = Files.createTempFile("obp-billing", ".properties")
    Files.write(file,
      List(
        "# comment",
        "obp.api.rootUrl = https://example.org",
        "obp.api.versionedUrl=$" + "{obp.api.rootUrl}/obp/v5.1.0",
        "obp.api.directloginUrl=$" + "{ obp.api.rootUrl }/my/logins/direct",
        "empty.value=",
        "").mkString("\n").getBytes(StandardCharsets.UTF_8))
    System.setProperty(Props.ConfigPathProperty, file.toString)
    try {
      assertEquals(Props.getProperty("obp.api.rootUrl"), "https://example.org")
      assertEquals(Props.getProperty("obp.api.versionedUrl"), "https://example.org/obp/v5.1.0")
      assertEquals(Props.getProperty("obp.api.directloginUrl"), "https://example.org/my/logins/direct")
      assertEquals(Props.getPropertyOpt("empty.value"), None)
      assertEquals(Props.getPropertyOpt("does.not.exist"), None)
      val err = intercept[IllegalArgumentException](Props.getProperty("does.not.exist"))
      assert(err.getMessage.contains("DOES_NOT_EXIST"))
    } finally {
      System.clearProperty(Props.ConfigPathProperty)
      Files.deleteIfExists(file)
    }
  }
}
