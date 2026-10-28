package com.unicarona.file.dto;

import java.util.UUID;

public record FileDto(UUID id,String purpose,String contentType,int byteSize) {}
