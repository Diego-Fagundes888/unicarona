package com.unicarona.file.controller;

import com.unicarona.auth.dto.AuthDto;
import com.unicarona.file.dto.FileDto;
import com.unicarona.file.service.FileService;
import com.unicarona.security.Actor;
import com.unicarona.shared.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController @RequestMapping("/api/v1/files") @Tag(name="Files") @SecurityRequirement(name="cookieAuth")
public class FileController {
    private final FileService service;
    public FileController(FileService service) { this.service=service; }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @Operation(summary="Enviar arquivo privado com tipo e tamanho validados")
    public FileDto upload(@AuthenticationPrincipal Actor actor,MultipartHttpServletRequest request) throws IOException,ServletException {
        var uploads=request.getMultiFileMap().values().stream().flatMap(java.util.Collection::stream).toList();
        ApiException.require(!uploads.isEmpty(),"FILE_REQUIRED","Selecione um arquivo.",422);
        var fields=request.getParts().stream().filter(part -> part.getSubmittedFileName()==null).toList();
        ApiException.require(uploads.size()==1 && fields.size()<=3,"INVALID_REQUEST","Requisição inválida.",413);
        var purposes=fields.stream().filter(part -> "purpose".equals(part.getName())).toList();
        ApiException.require(purposes.size()==1 && purposes.getFirst().getSize()<=16,"VALIDATION_ERROR","Confira os campos informados.",422);
        try(var content=purposes.getFirst().getInputStream()) {
            String purpose=new String(content.readNBytes(16),java.nio.charset.StandardCharsets.UTF_8);
            return service.upload(actor,purpose,uploads.getFirst().getBytes());
        }
    }
    @GetMapping("/{id}") @Operation(summary="Ler arquivo com autorização de proprietário, administrador ou participante vinculado")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal Actor actor,@PathVariable @Pattern(regexp=AuthDto.UUID_PATTERN) String id) throws IOException {
        var file=service.download(actor,UUID.fromString(id));
        var headers=new HttpHeaders();headers.setContentType(MediaType.parseMediaType(file.contentType()));headers.setCacheControl("private, no-store");
        if(!"photo".equals(file.purpose())) {
            headers.set(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"documento."+("application/pdf".equals(file.contentType())?"pdf":"imagem")+"\"");
            headers.set("Content-Security-Policy","default-src 'none'; sandbox");
        }
        return new ResponseEntity<>(file.data(),headers,HttpStatus.OK);
    }
}
