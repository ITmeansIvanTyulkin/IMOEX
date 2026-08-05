package com.moex.cointegration;

import com.moex.trinity.TrinityApplication;
import org.springframework.boot.SpringApplication;

/**
 * @deprecated use {@link com.moex.trinity.TrinityApplication}. Kept as a stable main-class alias.
 */
@Deprecated
public class CointegrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrinityApplication.class, args);
    }
}
