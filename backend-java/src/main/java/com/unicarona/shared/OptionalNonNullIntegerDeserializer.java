package com.unicarona.shared;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

public class OptionalNonNullIntegerDeserializer extends StdDeserializer<Integer> {
    public OptionalNonNullIntegerDeserializer() { super(Integer.class); }
    @Override public Integer deserialize(JsonParser parser,DeserializationContext context) throws IOException {
        if(!parser.currentToken().isNumeric()) throw JsonMappingException.from(parser,"Informe um inteiro válido.");
        try { return parser.getDecimalValue().intValueExact(); } catch(ArithmeticException error) { throw JsonMappingException.from(parser,"Informe um inteiro válido."); }
    }
    @Override public Integer getNullValue(DeserializationContext context) throws JsonMappingException { throw JsonMappingException.from(context.getParser(),"Este campo não aceita null."); }
    @Override public Integer getAbsentValue(DeserializationContext context) { return null; }
}
