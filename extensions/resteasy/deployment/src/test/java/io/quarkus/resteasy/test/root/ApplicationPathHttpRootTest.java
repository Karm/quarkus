package io.quarkus.resteasy.test.root;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Application;

import org.hamcrest.Matchers;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.restassured.RestAssured;

/**
 * Test a combination of application path and http root path.
 */
public class ApplicationPathHttpRootTest {

    @RegisterExtension
    static QuarkusUnitTest runner = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(HelloResource.class, HelloApp.class, JohnResource.class)
                    .addAsResource(new StringAsset("quarkus.http.root-path=/foo"), "application.properties"));

    @Test
    public void testGetResources() {
        // Note that /foo is added automatically by RestAssuredURLManager 
        RestAssured.when().get("/hello/world").then().body(Matchers.is("hello world"));
        RestAssured.when().get("/world").then().statusCode(404);
    }

    @Test
    public void testHeadResources() {
        // Note that /foo is added automatically by RestAssuredURLManager
        RestAssured.when().head("/hello/world").then()
                .header("content-length", Matchers.is("11")).and()
                .body(Matchers.empty());
    }

    @Test
    public void testJohnResource() {
        // Note that /foo is added automatically by RestAssuredURLManager
        RestAssured.when().get("/hello/john").then().body(Matchers.is("hello John"));
        RestAssured.when().head("/hello/john").then()
                .header("content-length", Matchers.is("10")).and()
                .body(Matchers.empty());
    }

    @Produces("text/plain")
    @Path("world")
    public static class HelloResource {

        @GET
        public String hello() {
            return "hello world";
        }

        @HEAD
        public String head() {
            return "hello world";
        }
    }

    @Produces("text/plain")
    @Path("john")
    public static class JohnResource {
        @GET
        public String john() {
            return "hello John";
        }
    }

    @ApplicationPath("hello")
    public static class HelloApp extends Application {
    }
}
