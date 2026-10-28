package com.unicarona.shared;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/** Distinguishes an omitted optional field from an explicit JSON null in record constructors. */
public class OptionalNonNullStringDeserializer extends StdDeserializer<String> {
    public OptionalNonNullStringDeserializer() { super(String.class); }
    @Override public String deserialize(JsonParser parser,DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) throw JsonMappingException.from(parser,"Informe um texto válido.");
        return parser.getText();
    }
    @Override public String getNullValue(DeserializationContext context) throws JsonMappingException {
        throw JsonMappingException.from(context.getParser(),"Este campo não aceita null.");
    }
    @Override public String getAbsentValue(DeserializationContext context) { return null; }
}
