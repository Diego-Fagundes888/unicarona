package com.unicarona.shared;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

public class OptionalNonNullDoubleDeserializer extends StdDeserializer<Double> {
    public OptionalNonNullDoubleDeserializer() { super(Double.class); }
    @Override public Double deserialize(JsonParser parser,DeserializationContext context) throws IOException {
        if(!parser.currentToken().isNumeric()) throw JsonMappingException.from(parser,"Informe um número válido.");
        double value=parser.getDoubleValue();
        if(!Double.isFinite(value)) throw JsonMappingException.from(parser,"Informe um número finito.");return value;
    }
    @Override public Double getNullValue(DeserializationContext context) throws JsonMappingException { throw JsonMappingException.from(context.getParser(),"Este campo não aceita null."); }
    @Override public Double getAbsentValue(DeserializationContext context) { return null; }
}
