package com.unicarona.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.core.converter.ModelConverters;
import com.unicarona.shared.ApiError;
import java.util.Set;

@Configuration
public class ApiDocumentation {
    @Bean OpenAPI openApi() {
        return new OpenAPI().info(new Info().title("UniCarona API — migração Java").version("1.0.0")
            .description("Endpoints Java implementados. O backend original permanece ativo até a validação completa da migração."))
            .components(new Components().addSecuritySchemes("cookieAuth",new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE).name("uc_access")));
    }
    @Bean OpenApiCustomizer compatibleSecurityHeadersAndErrors() {
        return api -> {
            ModelConverters.getInstance().read(ApiError.class).forEach((name,schema) -> api.getComponents().addSchemas(name,schema));
            api.getPaths().forEach((path,item) -> item.readOperationsMap().forEach((method,operation) -> {
                if (!path.startsWith("/api/v1/")) return;
                boolean mutation=!Set.of("GET","HEAD","OPTIONS").contains(method.name());
                if("/api/v1/files".equals(path) && mutation) {
                    var upload=new ObjectSchema().addProperty("purpose",new StringSchema()._enum(java.util.List.of("photo","identity","license","vehicle")))
                        .addProperty("file",new StringSchema().format("binary")).required(java.util.List.of("purpose","file"));
                    operation.requestBody(new RequestBody().required(true).description("Um arquivo de até 5 MB; foto até 2 MB, convertida para WebP de até 512 × 512. O tipo é conferido pelo conteúdo.")
                        .content(new Content().addMediaType("multipart/form-data",new MediaType().schema(upload))));
                }
                if("/api/v1/files/{id}".equals(path) && !mutation) {
                    var content=new Content();
                    for(String type:java.util.List.of("application/pdf","image/png","image/jpeg","image/webp")) content.addMediaType(type,new MediaType().schema(new StringSchema().format("binary")));
                    operation.getResponses().addApiResponse("200",new ApiResponse().description("Arquivo privado. Documentos são baixados como anexo; fotos somente para usuários autorizados.").content(content));
                }
                if ("GET".equals(method.name()) && Set.of("/api/v1/occurrences","/api/v1/blocks","/api/v1/admin/occurrences","/api/v1/admin/occurrences/{id}","/api/v1/reservations","/api/v1/subscriptions","/api/v1/admin/jobs","/api/v1/ride-requests","/api/v1/routines","/api/v1/routines/mine","/api/v1/rides","/api/v1/rides/mine","/api/v1/transfers","/api/v1/notifications","/api/v1/admin/academic-verifications","/api/v1/drivers/applications","/api/v1/admin/drivers/applications").contains(path)) {
                    operation.addParametersItem(new QueryParameter().name("limit").schema(new StringSchema().pattern("^\\d{1,3}$")._default("30")).description("Quantidade entre 1 e 100."));
                    operation.addParametersItem(new QueryParameter().name("offset").schema(new StringSchema().pattern("^\\d{1,4}$")._default("0")).description("Posição inicial entre 0 e 5000."));
                    if(path.endsWith("/drivers/applications")) operation.addParametersItem(new QueryParameter().name("status").schema(new StringSchema()._enum(java.util.List.of("pending","approved","rejected","replaced"))).description("Filtrar estado da solicitação."));
                    if(path.equals("/api/v1/admin/jobs")) operation.addParametersItem(new QueryParameter().name("status").schema(new StringSchema()._enum(java.util.List.of("pending","processing","done","dead"))._default("dead")));
                }
                if(operation.getParameters()!=null) operation.getParameters().stream().filter(p -> "idempotency-key".equals(p.getName())).forEach(p -> {
                    p.required(true).description("Chave de 16 a 128 caracteres: reutilize para repetir a mesma operação após falha de rede.").schema(new StringSchema().pattern("^[a-zA-Z0-9_-]{16,128}$"));
                });
                if("GET".equals(method.name()) && Set.of("/api/v1/admin/accounts","/api/v1/admin/pricing","/api/v1/admin/calendar").contains(path)) {
                    operation.addParametersItem(new QueryParameter().name("limit").schema(new StringSchema().pattern("^\\d{1,3}$")._default("30")).description("Quantidade entre 1 e 100."));
                    operation.addParametersItem(new QueryParameter().name("offset").schema(new StringSchema().pattern("^\\d{1,4}$")._default("0")).description("Posição entre 0 e 5000."));
                    if(path.endsWith("accounts")) {
                        operation.addParametersItem(new QueryParameter().name("q").schema(new StringSchema().minLength(1).maxLength(80)).description("Busca por nome e e-mail sem diferenciar acentos."));
                        operation.addParametersItem(new QueryParameter().name("status").schema(new StringSchema()._enum(java.util.List.of("active","suspended"))));
                    }else {
                        operation.addParametersItem(new QueryParameter().name("universityId").required(true).schema(new StringSchema().format("uuid")));
                        if(path.endsWith("calendar")) for(String name:java.util.List.of("startDate","endDate")) operation.addParametersItem(new QueryParameter().name(name).required(true).schema(new StringSchema().format("date")));
                    }
                }
                if("GET".equals(method.name()) && "/api/v1/rides".equals(path)) {
                    operation.addParametersItem(new QueryParameter().name("region").schema(new StringSchema().maxLength(100)).description("Busca por região, sem diferenciar acentos."));
                    operation.addParametersItem(new QueryParameter().name("direction").schema(new StringSchema()._enum(java.util.List.of("ida","volta"))._default("ida")));
                    operation.addParametersItem(new QueryParameter().name("date").schema(new StringSchema().pattern("^\\d{4}-\\d{2}-\\d{2}$")).description("Data válida; padrão: hoje em America/Sao_Paulo."));
                    operation.addParametersItem(new QueryParameter().name("time").schema(new StringSchema().pattern("^([01][0-9]|2[0-3]):[0-5][0-9]$")));
                    operation.addParametersItem(new QueryParameter().name("maxPrice").schema(new StringSchema().pattern("^\\d{1,9}$")).description("Preço máximo em centavos; zero conserva ausência de filtro."));
                    operation.addParametersItem(new QueryParameter().name("driverId").schema(new StringSchema().format("uuid")));
                    for(String name:java.util.List.of("automatic","onlyAvailable")) operation.addParametersItem(new QueryParameter().name(name).schema(new StringSchema()._enum(java.util.List.of("true","false"))));
                }
                if("GET".equals(method.name()) && "/api/v1/rides/mine".equals(path)) operation.addParametersItem(new QueryParameter().name("upcoming").schema(new StringSchema()._enum(java.util.List.of("true","false"))));
                if("GET".equals(method.name()) && "/api/v1/routines".equals(path)) {
                    operation.addParametersItem(new QueryParameter().name("region").schema(new StringSchema().maxLength(100)));
                    operation.addParametersItem(new QueryParameter().name("direction").schema(new StringSchema()._enum(java.util.List.of("ida","volta"))));
                    operation.addParametersItem(new QueryParameter().name("time").schema(new StringSchema().pattern("^([01][0-9]|2[0-3]):[0-5][0-9]$")));
                    operation.addParametersItem(new QueryParameter().name("compatibleOnly").schema(new StringSchema()._enum(java.util.List.of("true","false"))));
                }
                if("GET".equals(method.name()) && "/api/v1/ride-requests".equals(path)) {
                    operation.addParametersItem(new QueryParameter().name("side").schema(new StringSchema()._enum(java.util.List.of("passenger","driver"))._default("passenger")));
                    operation.addParametersItem(new QueryParameter().name("status").schema(new StringSchema()._enum(java.util.List.of("pending","accepted","confirmed","rejected","cancelled","expired"))));
                }
                if("GET".equals(method.name()) && "/api/v1/subscriptions".equals(path)) operation.addParametersItem(new QueryParameter().name("side").schema(new StringSchema()._enum(java.util.List.of("passenger","driver"))._default("passenger")));
                if("GET".equals(method.name()) && "/api/v1/reservations".equals(path)) {
                    operation.addParametersItem(new QueryParameter().name("side").schema(new StringSchema()._enum(java.util.List.of("passenger","driver"))._default("passenger")));
                    for(String name:java.util.List.of("rideId","contractId")) operation.addParametersItem(new QueryParameter().name(name).schema(new StringSchema().format("uuid")));
                    operation.addParametersItem(new QueryParameter().name("upcoming").schema(new StringSchema()._enum(java.util.List.of("true","false"))));
                }
                if(path.startsWith("/api/v1/webhooks/")) {
                    if(operation.getParameters()!=null) operation.getParameters().stream().filter(p -> Set.of("x-webhook-timestamp","x-webhook-signature").contains(p.getName())).forEach(p -> p.required(true));
                }
                if (mutation && !path.startsWith("/api/v1/webhooks/")) {
                    operation.addParametersItem(new HeaderParameter().name("Origin").required(true).schema(new StringSchema()).description("Origem configurada em APP_ORIGIN."));
                    if ("/api/v1/auth/refresh".equals(path) || (operation.getSecurity()!=null && !operation.getSecurity().isEmpty()))
                        operation.addParametersItem(new HeaderParameter().name("x-csrf-token").required(true).schema(new StringSchema()).description("Valor do cookie legível uc_csrf da mesma sessão."));
                }
                for (String status:new String[]{"400","401","403","404","409","413","415","422","429","500","503"})
                    operation.getResponses().addApiResponse(status,new ApiResponse().description(switch(status) {
                        case "400" -> "TOKEN_INVALID: link inválido, utilizado ou expirado; INVALID_REQUEST: multipart inválido.";
                        case "401" -> "UNAUTHENTICATED, INVALID_CREDENTIALS, SESSION_EXPIRED ou SESSION_REUSED.";
                        case "403" -> "ORIGIN_INVALID, CSRF_INVALID, EMAIL_UNVERIFIED, FORBIDDEN ou ACCOUNT_RESTRICTED.";
                        case "409" -> "Conflito de estado ou alteração concorrente.";
                        case "413" -> "INVALID_REQUEST: limite de tamanho ou quantidade de arquivos/campos excedido.";
                        case "415" -> "INVALID_REQUEST: formato da requisição não suportado.";
                        case "422" -> "VALIDATION_ERROR, UNDERAGE ou vínculo acadêmico inválido.";
                        case "429" -> "RATE_LIMITED: aguarde antes de tentar novamente.";
                        case "503" -> "SERVICE_UNAVAILABLE: falha temporária no serviço.";
                        default -> "Erro com code, message, details e requestId.";
                    }).content(new Content().addMediaType("application/json",new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError")))));
            }));
        };
    }
}
