package com.unicarona.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicarona.shared.ApiException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class RequestContractValidationTest {
    private final RequestContractValidation validation=new RequestContractValidation(new ObjectMapper());
    RequestContractValidationTest() throws Exception {}
    private static MockHttpServletRequest request(String method,String path,String body) {
        var request=new MockHttpServletRequest(method,path);request.setContentType("application/json");
        if(body!=null)request.setContent(body.getBytes(StandardCharsets.UTF_8));return request;
    }
    private void rejects(MockHttpServletRequest request,int status,String code) {
        var error=assertThrows(ApiException.class,() -> validation.validate(request));assertEquals(status,error.status());assertEquals(code,error.code());
    }
    @Test void protectedMutationRejectsMalformedFieldsBeforeAuthentication() {
        rejects(request("POST","/api/v1/auth/change-password","{}"),422,"VALIDATION_ERROR");
    }
    @Test void unknownOperationsReturnNotFoundBeforeAuthentication() {
        rejects(request("GET","/api/v1/missing",null),404,"NOT_FOUND");
        rejects(request("POST","/api/v1/catalogs","{}"),404,"NOT_FOUND");
        rejects(request("OPTIONS","/api/v1/users/me",null),404,"NOT_FOUND");
    }
    @Test void implicitHeadUsesGetQueryAndPathContracts() {
        assertDoesNotThrow(() -> validation.validate(request("HEAD","/api/v1/users/me",null)));
        var duplicate=request("HEAD","/api/v1/rides",null);duplicate.addParameter("limit","1","2");rejects(duplicate,422,"VALIDATION_ERROR");
        rejects(request("HEAD","/api/v1/rides/invalid-id",null),422,"VALIDATION_ERROR");
    }
    @Test void reservedSyntheticEmailDomainsKeepTheOriginalSyntaxRules() {
        assertDoesNotThrow(() -> validation.validate(request("POST","/api/v1/auth/login","{\"email\":\"synthetic@example.test\",\"password\":\"Fixture only\"}")));
        rejects(request("POST","/api/v1/auth/login","{\"email\":\"synthetic@example.123\",\"password\":\"Fixture only\"}"),422,"VALIDATION_ERROR");
    }
    @Test void nullableAcademicChoicesKeepTheOriginalEnumOrNullUnion() throws Exception {
        var body=new ObjectMapper().readTree("{\"name\":\"Synthetic Student\",\"acknowledgeMonthlyImpact\":false,\"academic\":{\"universityId\":\"00000000-0000-4000-8000-000000000001\",\"courseId\":\"00000000-0000-4000-8000-000000000002\",\"region\":\"Gama\",\"periodStatus\":\"irregular\",\"periodNumber\":null,\"classLabel\":null,\"shift\":\"variable\"}}");
        assertDoesNotThrow(() -> validation.validate(request("PATCH","/api/v1/users/me",body.toString())));
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("academic")).put("classLabel","D");rejects(request("PATCH","/api/v1/users/me",body.toString()),422,"VALIDATION_ERROR");
    }
    @Test void validBodyRemainsReadableByControllerAndPreservesRequestAttributes() throws Exception {
        String body="{\"email\":\"synthetic@example.com\",\"password\":\"Example!Only-2026\"}";
        var original=request("POST","/api/v1/auth/login",body);original.setAttribute("requestId","test-request");
        var cached=validation.validate(original);assertEquals(body,new String(cached.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
        assertEquals(body,cached.getReader().readLine());assertEquals("test-request",cached.getAttribute("requestId"));
    }
    @Test void parseErrorsRemainDifferentFromSchemaErrorsAndDoNotExposeInput() {
        var original=request("POST","/api/v1/auth/login","{\"password\":\"private-fixture\"");
        var error=assertThrows(ApiException.class,() -> validation.validate(original));assertEquals(400,error.status());assertEquals("INVALID_REQUEST",error.code());assertFalse(error.getMessage().contains("private-fixture"));
        rejects(request("POST","/api/v1/auth/login","{} {}"),400,"INVALID_REQUEST");
        rejects(request("POST","/api/v1/auth/login",""),400,"INVALID_REQUEST");
    }
    @Test void jsonBudgetIsBoundedBeforeSchemaValidation() {
        rejects(request("POST","/api/v1/auth/login"," ".repeat(256*1024+1)),413,"INVALID_REQUEST");
    }
    @Test void bodyFieldsAreNotCoercedOrSilentlyRemoved() {
        rejects(request("POST","/api/v1/auth/login","{\"email\":\"synthetic@example.com\",\"password\":123}"),422,"VALIDATION_ERROR");
        rejects(request("POST","/api/v1/auth/login","{\"email\":\"synthetic@example.com\",\"password\":\"ok\",\"extra\":true}"),422,"VALIDATION_ERROR");
        rejects(request("POST","/api/v1/auth/login","{\"email\":\"invalid-email\",\"password\":\"ok\"}"),422,"VALIDATION_ERROR");
    }
    @Test void literalRoutesWinAndInvalidResourceParametersAreRejected() throws Exception {
        assertDoesNotThrow(() -> validation.validate(request("GET","/api/v1/rides/mine",null)));
        rejects(request("GET","/api/v1/rides/invalid-id",null),422,"VALIDATION_ERROR");
    }
    @Test void strictQueriesRejectDuplicateAndUnexpectedValues() {
        var duplicate=request("GET","/api/v1/rides",null);duplicate.addParameter("page","1","2");rejects(duplicate,422,"VALIDATION_ERROR");
        var unexpected=request("GET","/api/v1/rides",null);unexpected.addParameter("unexpected","yes");rejects(unexpected,422,"VALIDATION_ERROR");
    }
    @Test void bodylessReadsAndMultipartKeepTheirExistingParsingPaths() throws Exception {
        assertDoesNotThrow(() -> validation.validate(request("GET","/api/v1/auth/me",null)));
        var multipart=request("POST","/api/v1/files","file-body");multipart.setContentType("multipart/form-data; boundary=example");
        assertSame(multipart,validation.validate(multipart));assertEquals("file-body",new String(multipart.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
    }
}
