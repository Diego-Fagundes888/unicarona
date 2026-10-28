package com.unicarona.shared;

import java.util.Map;

public record ApiError(String code, String message, Map<String, Object> details, String requestId) {}
