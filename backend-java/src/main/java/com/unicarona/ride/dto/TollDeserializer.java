package com.unicarona.ride.dto;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

public class TollDeserializer extends StdDeserializer<JsonNode> {
    public TollDeserializer() { super(JsonNode.class); }
    @Override public JsonNode deserialize(JsonParser parser,DeserializationContext context) throws IOException {
        if(parser.hasToken(JsonToken.VALUE_STRING)) {
            String value=parser.getText();if(!value.matches("\\d{1,4}(\\.\\d{1,2})?")) throw JsonMappingException.from(parser,"Informe um pedágio válido.");
        } else if(parser.currentToken().isNumeric()) {
            double value=parser.getDoubleValue();if(!Double.isFinite(value) || value<0 || value>2000) throw JsonMappingException.from(parser,"Informe um pedágio válido.");
        } else throw JsonMappingException.from(parser,"Informe um pedágio válido.");
        return parser.readValueAsTree();
    }
    @Override public JsonNode getNullValue(DeserializationContext context) throws JsonMappingException { throw JsonMappingException.from(context.getParser(),"Este campo não aceita null."); }
    @Override public JsonNode getAbsentValue(DeserializationContext context) { return null; }
}
