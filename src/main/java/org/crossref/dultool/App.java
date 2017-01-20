package org.crossref.dultool;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.Algorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.PlainObject;
import com.nimbusds.jose.JOSEObject;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.IOUtils;

public class App {
  private static String PRODUCER_LEVEL_1 = "1";
  private static String PRODUCER_LEVEL_2 = "2";
  private static String PRODUCER_LEVEL_3 = "3";

  private static List<String> PRODUCER_LEVELS = Arrays.asList(PRODUCER_LEVEL_1, PRODUCER_LEVEL_2, PRODUCER_LEVEL_3);

  private static String CONSUMER_STRICT = "strict";
  private static String CONSUMER_RELAXED = "relaxed";

  private static List<String> CONSUMER_LEVELS = Arrays.asList(CONSUMER_STRICT, CONSUMER_RELAXED);

  private static String HMAC_SECRET = "dul-77d343c3-f8e8-48d9-9e14-1e52aa8611e8";

  private static String WHITELIST_URL_PREFIX = "https://dul-token.crossref.org/tokens/jwk/";

  private static List<JWSAlgorithm> STRICT_ALGORITHMS = Arrays.asList(JWSAlgorithm.RS256, JWSAlgorithm.HS256);

  private static void sign(String producerLevel, String jkuUrl, String producerId, InputStream input, OutputStream output, String jwkPath) {
    try {
      String content = IOUtils.toString(input, Charset.forName("UTF-8"));
      Payload payload = new Payload(content);
      
      // Select algorithm based on PRODUCER_LEVEL.
      JWSAlgorithm algorithm = null;
      JWSSigner signer = null;
      if (producerLevel.equals(PRODUCER_LEVEL_1)) {
        // No algorithm, we don't sign.
      } else if (producerLevel.equals(PRODUCER_LEVEL_2)) {
        algorithm = JWSAlgorithm.HS256;
        signer = new MACSigner(HMAC_SECRET);

      } else if (producerLevel.equals(PRODUCER_LEVEL_3)) {
        // Check required params.
        if (jwkPath == null || jwkPath.length() == 0 || !Files.exists(Paths.get(jwkPath))) {
          helpAndExit("JWK not supplied or doesn't exist.");
        }

        if (jkuUrl == null || jkuUrl.length() == 0) {
          helpAndExit("JKU_URL not supplied.");
        }

        algorithm = JWSAlgorithm.RS256;
        
        String keyContent = IOUtils.toString(new FileInputStream(jwkPath), Charset.forName("UTF-8"));
        JWK jwk = JWK.parse(keyContent);
        RSAKey rsa = (RSAKey)jwk;
        signer = new RSASSASigner(rsa);

      } else {
        helpAndExit("Unexpected PRODUCER_LEVEL");
      }

      // Level 1 gets no signature
      // Levels 2 and 3 get a signature.
      if (producerLevel.equals(PRODUCER_LEVEL_1)) {
        Map<String, Object> customParams = new HashMap<String, Object>();
        customParams.put("iss", producerId);
        PlainHeader header = new PlainHeader(null, null, null, customParams, null);
        PlainObject object = new PlainObject(header, payload);

        output.write(object.serialize().getBytes("UTF-8"));

      } else {
        // Create a JWS with the specified algorithm depending on level.
        // Set the `iss` header to the PRODUCER_ID.
        JWSHeader.Builder builder = new JWSHeader.Builder(algorithm).customParam("iss", producerId);

        if (producerLevel.equals(PRODUCER_LEVEL_3)) {
          builder = builder.jwkURL(new URI(jkuUrl));
        }

        JWSObject jwsObject = new JWSObject(builder.build(), payload);
        jwsObject.sign(signer);
        output.write(jwsObject.serialize().getBytes("UTF-8"));
      }

    } catch (Exception e) {
      e.printStackTrace();
      System.out.println(e.getMessage());

    }
  }

  private static void validate(String consumerLevel, InputStream input, OutputStream output) {
    try {
      String token = IOUtils.toString(input, Charset.forName("UTF-8"));

      JOSEObject object = JOSEObject.parse(token);
      Algorithm algorithm = object.getHeader().getAlgorithm();
      String issuer = (String)object.getHeader().getCustomParam("iss");

      // Issuer must be present in all cases.
      // Furthermore, allowing an empty issuer would provide a security hole for authenticity validation with the URL prefix.
      if (issuer == null || issuer.length() < 2) {
        fatal("Issuer header missing from JWT.");
      }

      // Send info to stderr out of stdout stream.
      System.err.println("Sender: " + issuer);

      if (consumerLevel.equals(CONSUMER_RELAXED)) {
        // Relaxed mode does no validation.
        output.write(object.getPayload().toBytes());

      } else if (consumerLevel.equals(CONSUMER_STRICT)) {
        if (!STRICT_ALGORITHMS.contains(algorithm)) {
          fatal("Algorithm " + algorithm.toString() + " not allowed in strict mode.");
        }

        JWSObject jwsObject = JWSObject.parse(token);

        if (algorithm.equals(JWSAlgorithm.HS256)) {
          JWSVerifier verifier = new MACVerifier(HMAC_SECRET);

          boolean success = jwsObject.verify(verifier);

          if (!success) {
            fatal("Could not verify JWS with HMAC.");
          } else {
            output.write(jwsObject.getPayload().toBytes());
          }

        } else if (algorithm.equals(JWSAlgorithm.RS256)) {
          URL jwkUrl = jwsObject.getHeader().getJWKURL().toURL();

          if (!jwkUrl.toString().startsWith(WHITELIST_URL_PREFIX)) {
            fatal("JKU URL does not have whitelisted prefix. Expected: " + WHITELIST_URL_PREFIX + " but found: " + jwkUrl.toString());
          }

          // Match up the issuer with the URL for the JWK.
          String expectedPrefix = WHITELIST_URL_PREFIX + issuer;
          if (!jwkUrl.toString().startsWith(expectedPrefix)) {
            fatal("JKU URL does not have whitelisted prefix for issuer. Expected: " + expectedPrefix + " but found: " + jwkUrl.toString());
          }

          // If it's OK, load the JWK and get the first one.
          JWKSet publicKeys = JWKSet.load(jwkUrl);
          RSAKey key = (RSAKey)publicKeys.getKeys().get(0);

          JWSVerifier verifier = new RSASSAVerifier(key);

          boolean success = jwsObject.verify(verifier);

          if (!success) {
            fatal("Could not verify JWS with RSA.");
          } else {
            output.write(jwsObject.getPayload().toBytes());
          }
        }
      }

} catch (Exception e) {
System.out.println("EX " + e.getMessage());


}
    

  }



  private static void helpAndExit(String errorMessage) {
    if (errorMessage != null) {
      System.err.println("Error: " + errorMessage);
    }

    System.err.println("Usage: ");
    System.err.println("java -jar dultool.jar command in-file out-file");
    System.err.println("");
    System.err.println("command: one of \"sign\" or \"validate\"");
    System.err.println("in-file: path of input file or - for STDIN");
    System.err.println("out-file: path of output file or - for STDOUT");
    System.err.println("");
    System.err.println("When the \"sign\" command is used the following environment variables are required:\"");


    System.err.println("PRODUCER_ID: the unique ID of the producer, supplied by Crossref");
    System.err.println("PRIVATE_KEY: path to the corresponding RSA private key file");

    System.err.println("");

    System.exit(1);
  }

  private static void fatal(String errorMessage) {
    if (errorMessage != null) {
      System.err.println("Error: " + errorMessage);
    }
    System.exit(1);
  }




  private static InputStream getInput(String arg) {
    try {
      if (arg.equals("-")) {
        return System.in;
      } else {
        return new FileInputStream(arg);
      }
    } catch (FileNotFoundException ex) {
      helpAndExit("Input file does not exist.");
    }

    return null;
  }

  private static OutputStream getOutput(String arg) {
    try {
      if (arg.equals("-")) {
        return System.out;
      } else {
        return new FileOutputStream(arg);
      }
    } catch (FileNotFoundException ex) {
      helpAndExit("Output file does not exist.");
    }

    return null;
  }

  private static String getProducerLevel() {
    String value = System.getenv("PRODUCER_LEVEL");

    if (value == null || value.length() == 0) {
      value = "3";
    }

    if (!PRODUCER_LEVELS.contains(value)) {
      helpAndExit("Unrecognised PRODUCER_LEVEL."); 
    }

    return value;
  }

  private static String getConsumerLevel() {
    String value = System.getenv("CONSUMER_LEVEL");

    if (value == null || value.length() == 0) {
      value = "3";
    }

    if (!CONSUMER_LEVELS.contains(value)) {
      helpAndExit("Unrecognised CONSUMER_LEVEL."); 
    }

    return value;
  }

  private static String getConsumerId() {
    // 4.1: The `CONSUMER_LEVEL` environment variable can be supplied. If supplied it can be 1, 2 or 3. If not supplied the default value is 3.
    String value = System.getenv("CONSUMER_LEVEL");

    if (value == null || value.length() == 0) {
      value = "3";
    }

    if (!CONSUMER_LEVELS.contains(value)) {
      helpAndExit("Unrecognised CONSUMER_LEVEL."); 
    }

    return value;
  }

  public static void main(String[] args) {
    if (args.length != 3) {
      helpAndExit("Not enough arguments.");
    }

    InputStream input = getInput(args[1]);
    OutputStream output = getOutput(args[2]);

    if (args[0].equals("sign")) {

      String producerLevel = getProducerLevel();
      String producerId = System.getenv("PRODUCER_ID");

      // These can be null, but are checked before they're needed.
      String jwkPath = System.getenv("JWK");
      String jkuUrl = System.getenv("JKU_URL");

      if (producerId == null || producerId.length() == 0) {
        helpAndExit("PRODUCER_ID not supplied");
      }

      // todo check null url and produer id

      sign(producerLevel, jkuUrl, producerId, input, output, jwkPath);
    
    } else if (args[0].equals("validate")) {
      String consumerLevel = getConsumerLevel();

      validate(consumerLevel, input, output);
    } else {
      helpAndExit("didn't recognise command '" + args[0] + "'.");
    }

    System.exit(0);
  }
}

