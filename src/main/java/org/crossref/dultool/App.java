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

abstract class DulException extends Exception {
}

class WrongConsumerLevelException extends DulException {
  private String level;
  private String algorithm;

  WrongConsumerLevelException(String level, String algorithm) {
    this.level = level;
    this.algorithm = algorithm;
  }

  public String getMessage() {
    return "Consumer Level " + this.level + " incompatible with algorithm " + this.algorithm;
  }
}

class MissingJwkException extends DulException {
  private String supplied;

  MissingJwkException(String supplied) {
    this.supplied = supplied;
  }

  public String getMessage() {
    return String.format("JWK not supplied or didn't exist. Supplied: %s", this.supplied);
  }
}

class MissingJkuUrlException extends DulException {
  private String supplied;

  MissingJkuUrlException(String supplied) {
    this.supplied = supplied;
  }

  public String getMessage() {
    return String.format("JKU_URL not supplied or didn't exist. Supplied: %s", this.supplied);
  }
}

class BadJkuUrlPrefixException extends DulException {
  private String supplied;
  private String expected;

  BadJkuUrlPrefixException(String supplied, String expected) {
    this.supplied = supplied;
    this.expected = expected;
  }

  public String getMessage() {
    return String.format("JKU_URL had the wrong prefix. Got '%s', expected '%s'", supplied, expected);
  }
}

class BadJkuUrlProducerException extends DulException {
  private String supplied;
  private String expected;

  BadJkuUrlProducerException(String supplied, String expected) {
    this.supplied = supplied;
    this.expected = expected;
  }

  public String getMessage() {
    return String.format("JKU_URL had the wrong prefix for the PRODUCER_ID. Got '%s', expected '%s'", supplied, expected);
  }
}

class RsaVerificationException extends DulException {
  public String getMessage() {
    return "Failed to validate input with RSA (Level 3) signature.";
  }
}

class HmacVerificationException extends DulException {
  public String getMessage() {
    return "Failed to validate input with HMAC (Level 2) signature.";
  }
}

class StrictModeCompatibilityException extends DulException {
  private String algorithm;

  public StrictModeCompatibilityException(String algorithm) {
    this.algorithm = algorithm;
  }

  public String getMessage() {
    return String.format("Strict mode does not allow the algorithm: %s", this.algorithm);
  }
}

class IssuerUnknownException extends DulException {
  public String getMessage() {
    return "The PRODUCER_ID was not supplied in the 'iss' header field.";
  }
}

public class App {
  private static String PRODUCER_LEVEL_1 = "1";
  private static String PRODUCER_LEVEL_2 = "2";
  private static String PRODUCER_LEVEL_3 = "3";

  private static List<String> PRODUCER_LEVELS = Arrays.asList(PRODUCER_LEVEL_1, PRODUCER_LEVEL_2, PRODUCER_LEVEL_3);

  private static String CONSUMER_STRICT = "strict";
  private static String CONSUMER_RELAXED = "relaxed";

  private static List<String> CONSUMER_LEVELS = Arrays.asList(CONSUMER_STRICT, CONSUMER_RELAXED);

  private static String HMAC_SECRET = "dul-77d343c3-f8e8-48d9-9e14-1e52aa8611e8";

  private static String WHITELIST_URL_PREFIX = "https://dul.crossref.org/tokens/jwk/";

  private static List<JWSAlgorithm> STRICT_ALGORITHMS = Arrays.asList(JWSAlgorithm.RS256, JWSAlgorithm.HS256);

  // Sign the input with CONSUMER_LEVEL 1
  private static void sign1(InputStream input, OutputStream output, String producerId) {
    try {
      String content = IOUtils.toString(input, Charset.forName("UTF-8"));
      Payload payload = new Payload(content);

      Map<String, Object> customParams = new HashMap<String, Object>();
      customParams.put("iss", producerId);

      PlainHeader header = new PlainHeader(null, null, null, customParams, null);
      PlainObject object = new PlainObject(header, payload);

      output.write(object.serialize().getBytes("UTF-8"));  
    } catch (IOException ex) {
      fatal("Error with input or output.");
    }
  }

  // Sign the input with CONSUMER_LEVEL 2
  private static void sign2(InputStream input, OutputStream output, String producerId) {
    try {
      String content = IOUtils.toString(input, Charset.forName("UTF-8"));
      Payload payload = new Payload(content);

      JWSAlgorithm algorithm = JWSAlgorithm.HS256;
      JWSSigner signer = new MACSigner(HMAC_SECRET);

      // Set the `iss` header to the PRODUCER_ID.
      JWSHeader.Builder builder = new JWSHeader.Builder(algorithm)
        .customParam("iss", producerId);

      JWSObject jwsObject = new JWSObject(builder.build(), payload);
      jwsObject.sign(signer);
      output.write(jwsObject.serialize().getBytes("UTF-8"));
    } catch (com.nimbusds.jose.KeyLengthException ex) {
      fatal("Invalid input supplied.");
    } catch (com.nimbusds.jose.JOSEException ex) {
      fatal("Unexpected eror signing with HMAC.");
    } catch (IOException ex) {
      System.err.println("Error with input or output.");
    }
  }

  // Sign the input with CONSUMER_LEVEL 2
  private static void sign3(InputStream input, OutputStream output, String producerId, String jkuUrl, String jwkPath) {
    try {
      String content = IOUtils.toString(input, Charset.forName("UTF-8"));
      Payload payload = new Payload(content);

      // Check required params.
      if (jwkPath == null || jwkPath.length() == 0 || !Files.exists(Paths.get(jwkPath))) {
        throw new MissingJwkException(jwkPath);
      }

      if (jkuUrl == null || jkuUrl.length() == 0) {
        throw new MissingJkuUrlException(jkuUrl);
      }

      if (!jkuUrl.startsWith(WHITELIST_URL_PREFIX)) {
        throw new BadJkuUrlPrefixException(jkuUrl, WHITELIST_URL_PREFIX);
      }

      if (!jkuUrl.startsWith(WHITELIST_URL_PREFIX.concat(producerId))) {
        throw new BadJkuUrlProducerException(jkuUrl, WHITELIST_URL_PREFIX.concat(producerId));
      }

      JWSAlgorithm algorithm = JWSAlgorithm.RS256;
      
      String keyContent = IOUtils.toString(new FileInputStream(jwkPath), Charset.forName("UTF-8"));
      JWK jwk = JWK.parse(keyContent);
      RSAKey rsa = (RSAKey)jwk;
      JWSSigner signer = new RSASSASigner(rsa);

      JWSHeader.Builder builder = new JWSHeader.Builder(algorithm).customParam("iss", producerId).jwkURL(new URI(jkuUrl));

      JWSObject jwsObject = new JWSObject(builder.build(), payload);
      jwsObject.sign(signer);
      output.write(jwsObject.serialize().getBytes("UTF-8"));
    
    } catch (java.net.URISyntaxException ex) {
      fatal("JKU_URL invalid");
    } catch (java.io.FileNotFoundException ex) {
      fatal("JWK file does not exist.");
    } catch (java.text.ParseException ex) {
      fatal("Error reading JWK.");
    } catch (com.nimbusds.jose.JOSEException ex) {
      fatal("Unexpected eror signing with RSA.");
    } catch (IOException ex) {
      fatal("Error with reading or writing.");
    } catch (DulException ex) {
      fatal(ex.getMessage());
    }
  }

  // Validate message, send output to output stream and return sender ID.
  // Or return null.
  private static String validate(boolean strict, InputStream input, OutputStream output) {
    String issuer = null;
    try {
      String token = IOUtils.toString(input, Charset.forName("UTF-8"));

      JOSEObject object = JOSEObject.parse(token);
      Algorithm algorithm = object.getHeader().getAlgorithm();
      issuer = (String)object.getHeader().getCustomParam("iss");

      // Issuer must be present in all cases.
      // Furthermore, allowing an empty issuer would provide a security hole for authenticity validation with the URL prefix.
      if (issuer == null || issuer.length() < 2) {
        throw new IssuerUnknownException();
      }

      // Relaxed mode does no validation.
      if (!strict) {
        output.write(object.getPayload().toBytes());
        output.write("\n".getBytes("UTF-8"));

      // Strict mode now needs to verify the signature.
      } else {

        if (!STRICT_ALGORITHMS.contains(algorithm)) {
          throw new StrictModeCompatibilityException(algorithm.toString());
        }

        JWSObject jwsObject = JWSObject.parse(token);

        // HMAC tokens produced by Level 2.
        if (algorithm.equals(JWSAlgorithm.HS256)) {

          // Use constant, public secret.
          JWSVerifier verifier = new MACVerifier(HMAC_SECRET);

          boolean success = jwsObject.verify(verifier);

          if (!success) {
            throw new HmacVerificationException();
          } else {
            output.write(jwsObject.getPayload().toBytes());
            output.write("\n".getBytes("UTF-8"));
          }

        // RSA tokens produced by Level 3.
        } else if (algorithm.equals(JWSAlgorithm.RS256)) {
          URL jwkUrl = jwsObject.getHeader().getJWKURL().toURL();

          if (!jwkUrl.toString().startsWith(WHITELIST_URL_PREFIX)) {
            throw new BadJkuUrlPrefixException(jwkUrl.toString(), WHITELIST_URL_PREFIX);
          }

          // Match up the issuer with the URL for the JWK.
          String expectedPrefix = WHITELIST_URL_PREFIX.concat(issuer);
          if (!jwkUrl.toString().startsWith(expectedPrefix)) {
            throw new BadJkuUrlProducerException(jwkUrl.toString(), expectedPrefix);
          }

          // If it's OK, load the JWK and get the first one.
          JWKSet publicKeys = JWKSet.load(jwkUrl);
          RSAKey key = (RSAKey)publicKeys.getKeys().get(0);

          JWSVerifier verifier = new RSASSAVerifier(key);

          boolean success = jwsObject.verify(verifier);

          if (!success) {
            throw new RsaVerificationException();
          } else {
            output.write(jwsObject.getPayload().toBytes());
            output.write("\n".getBytes("UTF-8"));
          }
        }
      }
    } catch (java.net.MalformedURLException ex) {
      fatal("JKU was invalid.");
      issuer = null;
    } catch (IOException ex) {
      fatal("Error with reading or writing.");
      issuer = null;
    } catch (java.text.ParseException ex) {
      fatal("Error parsing input message.");
      issuer = null;
    } catch (com.nimbusds.jose.JOSEException ex) {
      fatal("Unexpected error validating.");
      issuer = null;
    } catch (DulException ex) {
      fatal(ex.getMessage());
      issuer = null;
    } finally {
      return issuer;
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

      if (producerId == null || producerId.length() == 0) {
        helpAndExit("PRODUCER_ID not supplied");
      }

      // Choose which level of signing.
      if (producerLevel.equals(PRODUCER_LEVEL_1)) {
        sign1(input, output, producerId);
      } else if (producerLevel.equals(PRODUCER_LEVEL_2)) {
        sign2(input, output, producerId);
      } else if (producerLevel.equals(PRODUCER_LEVEL_3)) {
        String jwkPath = System.getenv("JWK");
        String jkuUrl = System.getenv("JKU_URL");

        sign3(input, output, producerId, jkuUrl, jwkPath);
      } else {
        

        helpAndExit(String.format("Unrecognised PRODUCER_LEVEL of: %s", producerLevel));
      }
    } else if (args[0].equals("validate")) {
      String consumerLevel = getConsumerLevel();

      boolean strict = true;
      if (consumerLevel.equals(CONSUMER_STRICT)) {
        strict = true;
      } else if (consumerLevel.equals(CONSUMER_RELAXED)) {
        strict = false;
      } else {
        helpAndExit(String.format("Unrecognised CONSUMER_LEVEL value of: %s", consumerLevel));
      }

      String producerId = validate(strict, input, output);
      
      System.out.println(producerId);
    } else {
      helpAndExit("didn't recognise command '" + args[0] + "'.");
    }

    System.exit(0);
  }
}

