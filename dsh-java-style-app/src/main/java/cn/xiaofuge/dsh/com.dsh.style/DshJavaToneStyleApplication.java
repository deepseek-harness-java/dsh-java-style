package cn.xiaofuge.dsh.com.dsh.style;

import cn.xiaofuge.dsh.com.dsh.style.config.ToneStyleProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ToneStyleProperties.class)
public class DshJavaToneStyleApplication {

    public static void main(String[] args) {
        SpringApplication.run(DshJavaToneStyleApplication.class, args);
    }
}
