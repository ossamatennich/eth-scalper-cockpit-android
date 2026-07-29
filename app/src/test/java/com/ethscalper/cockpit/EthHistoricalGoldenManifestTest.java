package com.ethscalper.cockpit;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public final class EthHistoricalGoldenManifestTest {
    @Test public void currentEthMatchesImmutableHistoricalManifest() throws Exception {
        Map<String,String> actual=EthHistoricalTrace.generate();
        String output=System.getProperty("ethGoldenOutput","");
        if(output.isEmpty())output=System.getenv().getOrDefault("ETH_GOLDEN_OUTPUT","");
        if(!output.isEmpty()) {
            StringBuilder text=new StringBuilder();
            for(Map.Entry<String,String> entry:actual.entrySet())
                text.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            Path target=Path.of(output);Files.createDirectories(target.getParent());
            Files.write(target,text.toString().getBytes(StandardCharsets.UTF_8));return;
        }
        Properties expected=new Properties();
        try(InputStream input=getClass().getResourceAsStream("/eth_v23321_golden_manifest.properties")) {
            assertNotNull("Immutable historical reference is missing",input);expected.load(input);
        }
        for(Map.Entry<String,String> entry:actual.entrySet())
            assertEquals(entry.getKey(),expected.getProperty(entry.getKey()),entry.getValue());
    }
}
