package io.quarkus.runtime.graal;

import java.util.function.BooleanSupplier;

public final class IsFIPSEnabled implements BooleanSupplier {

    @Override
    public boolean getAsBoolean() {
        try {
            Class.forName("sun.security.pkcs11.wrapper.PKCS11$FIPSPKCS11");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
