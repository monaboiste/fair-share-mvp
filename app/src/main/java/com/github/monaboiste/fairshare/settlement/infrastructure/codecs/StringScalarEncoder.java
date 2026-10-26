package com.github.monaboiste.fairshare.settlement.infrastructure.codecs;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.util.function.Function;

final class StringScalarEncoder<T> extends JsonSerializer<T> {
    private final Function<T, String> encode;

    StringScalarEncoder(Function<T, String> encode) {
        this.encode = encode;
    }

    @Override
    public void serialize(T value, JsonGenerator generator, SerializerProvider provider) throws IOException {
        generator.writeString(encode.apply(value));
    }
}
