package com.unicarona.shared;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.math.*;

/** JSON.stringify number spelling for the normalized request fingerprint, without a Node runtime. */
public final class JavaScriptNumbers {
    private JavaScriptNumbers() {}
    public static String format(double value) {
        if(!Double.isFinite(value)) throw new IllegalArgumentException("A JSON number must be finite.");
        if(value==0) return "0";
        var exact=new BigDecimal(value); BigDecimal shortest=null;
        for(int digits=1;digits<=17;digits++) {
            var candidate=exact.round(new MathContext(digits,RoundingMode.HALF_EVEN));
            if(Double.doubleToLongBits(candidate.doubleValue())==Double.doubleToLongBits(value)) { shortest=candidate.stripTrailingZeros();break; }
        }
        if(shortest==null) throw new IllegalStateException("Could not represent a JSON number.");
        int exponent=shortest.precision()-shortest.scale()-1;
        if(exponent>=-6 && exponent<21) return shortest.toPlainString();
        var mantissa=shortest.movePointLeft(exponent).stripTrailingZeros();
        return mantissa.toPlainString()+"e"+(exponent>=0?"+":"")+exponent;
    }
    public static class DoubleSerializer extends StdSerializer<Double> {
        public DoubleSerializer() { super(Double.class); }
        @Override public void serialize(Double value,JsonGenerator gen,SerializerProvider provider) throws IOException { gen.writeNumber(format(value)); }
    }
    public static class ScalarSerializer extends StdSerializer<JsonNode> {
        public ScalarSerializer() { super(JsonNode.class); }
        @Override public void serialize(JsonNode value,JsonGenerator gen,SerializerProvider provider) throws IOException {
            if(value.isNumber()) gen.writeNumber(format(value.doubleValue()));else gen.writeString(value.textValue());
        }
    }
}
