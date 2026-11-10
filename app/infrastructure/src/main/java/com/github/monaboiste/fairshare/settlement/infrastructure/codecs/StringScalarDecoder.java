package com.github.monaboiste.fairshare.settlement.infrastructure.codecs;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.util.function.Function;

final class StringScalarDecoder<T> extends JsonDeserializer<T> {
    private final Class<T> type;
    private final Function<String, T> decode;

    StringScalarDecoder(Class<T> type, Function<String, T> decode) {
        this.type = type;
        this.decode = decode;
    }

    @Override
    public T deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return type.cast(context.handleUnexpectedToken(type, parser));
        }
        return decode.apply(parser.getText());
    }
}
