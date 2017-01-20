# DUL Tool

Tool for producing Authenticated DUL messages and validating them. For use in production or as a reference implementation. It is a very thin wrapper around the open source connect2id [Nimbus JOSE + JWT library](http://connect2id.com/products/nimbus-jose-jwt).

## To run

The utility will accept a single JSON message and sign it to create a JWT, or reverse the process. 

Usage:

  java -jar dultool.jar command input-file output-file

Command is one of:

 - `sign`: Produce a JWT
 - `validate`: Validate a JWT and return the JSON, if valid

The input-file can be a path to a file or - for STDIN. The output-file can be a path to a file or - for STDOUT.

When the `validate` command is used:

 - The Consumer level is `strict` by default.
 - It can be specified as `strict` or `relaxed` using the `CONSUMER_LEVEL` environment variable.

When the `sign` command is used:
 - The Producer level is 3 by default.
 - The `PRODUCER_ID` environment variable must be supplied. This is the unique ID of the producer, supplied by Crossref
 - It can be specified as level 1, 2 or 3 using the `PRODUCER_LEVEL` environment variable.
 - If `PRODUCER_LEVEL` 3 is specified the following environemnt environment variables are required:

	 - `JWK` - path to a JKU file containing a private key as a JWK
	 - `JKU_URL` - JKU path to the JWK containing only the public key, as supplied by Crossref.

## Generating JKU keys

Public and Private Keys can be generated using Nimbus JWT Generator, available here: http://connect2id.com/products/nimbus-jose-jwt/generator

	java -jar json-web-key-generator.jar -t RSA -s 2048 -i example-provider -u sig -S -p

The private key should be kept secret and used as the `JWK` argument. The public key should be sent to Crossref, who will supply you with a `JKU_URL`.

## Specification

This tool is a reference implementation of normative Specification from the Crossref Distributed Usage Logging Message Authentication Recommendation.

The tool conforms to the following specifications:

1. If `PRODUCER_LEVEL` is 3, then `JWK` and `JKU_URL` environment variables must be supplied.
2. There is a hard-coded HMAC256 secret that must be used whenever a HMAC signature is used. Its value is "dul-77d343c3-f8e8-48d9-9e14-1e52aa8611e8",
3. When the 'sign' command is used:
	1. The tool will accept an input from a file or STDIN
	2. The output will be a JWT, signed and sent to the output file or STDOUT
	4. The input will be the payload of the JWT.
  5. The `PRODUCER_LEVEL` environment variable can be supplied. If supplied it can be 1, 2 or 3. If not supplied, the default value is 3. 
	6. The `PRODUCER_ID` will be used as the `iss` header field.
	7. The tool does not care about the format of the input. 
4. When the 'validate' command is used
  1. The `CONSUMER_LEVEL` environment variable can be supplied. If supplied it can be 'strict' or 'relaxed'. If not supplied the default value is 'strict'.
	2. The tool will accept an input from a file or STDIN, as a JWT.
	3. It will validate the input according to the rules specified below
	4. On success, the output will be the input's payload, sent to the file or STDOUT.
	5. If the input cannot be validated, errors will be sent to STDERR, nothing will be sent to the output, and the program exit code will be non-zero.
5. If `PRODUCER_LEVEL` is 3 and `sign` is invoked:
	1. The input will be signed using the 'rsa256' algorithm, as specified in the 'alg' header.
	2. a path to a valid RSA JWK must be provided with `JWK` option
	3. a URL that contains the public JWK must be provided with the `JWK_URL` option
	4. the input will be signed using RSA256 and the JWK private key
6. If `PRODUCER_LEVEL` is 2 and 'sign' is invoked:
	1. The input will be signed using the 'hmac256' algorithm, as specified in the 'alg' header.
	2. The hard-coded DUL secret of will be used for HMAC signing
7. If `PRODUCER_LEVEL` is 1 and 'sign' is invoked:
	1. The input will be signed using the 'none' algorithm, as specified in the 'alg' header.
	2. No signature will be attacehd, as per the 'none' algorithm's specification
8. If `validate` is invoked and validation is successful:
	1. The payload plus a `\n` character will be sent to the stipulated file or STDOUT
	2. The producer id will be sent to STDOUT after the payload is sent to the output.
	3. The process will exit with an exit code of 0
9. If `validation` is invoked and validation is unsuccessfu:
	1. Any error messages will be sent to STDERR.
	2. The process will exit with a non-zero exit code.
10. If `CONSUMER_LEVEL` is 'strict' and `validate` is invoked:
	1. The `iss` header field must be present.
	2. The `alg` header field must be 'rsa256' or 'hmac256'. Otherwise validation will fail.
  3. If the `alg` header field is 'hmac256':
		1. The JWS must validate using the 'hmac256' algorithm and the hard-coded secret.
 	4. If the `alg` header field is 'rsa256':
		1. The `jku` header field must be present or validation will fail.
		2. The `jku` header field must be a URL that has the prefix "https://dul-token.crossref.org/tokens/jwk/PRODUCER_ID", where `PRODUCER_ID` exactly matches the `iss` header field. If the URL does not match the validation will fail.
		3. There must be a valid public key JWK available at the given URL.
		4. The first available key will be taken from the keyset if more than one is available.
		5. The JWS of the JWT must succeed using the RSA256 algorithm and this key.
11. If `CONSUMER_LEVEL` is 'relaxed' and 'validate' is invoked:
	1. The `iss` header field must be present.
	2. The `alg` header is ignored.
	3. The message is accepted whether or not it has a signature.
	4. No signature checking is performed. Inputs from `PRODUCER_LEVEL` 1, 2 and 3 can be read, but no validation of any form will be performed.
12. The output from `PRODUCER_LEVEL` 1, 2 and 3 can be consumed but not verified by `CONSUMER_LEVEL` of 'relaxed'.
13. The output from `PRODUCER_LEVEL` 1, and 3 can be consumed and verified by `CONSUMER_LEVEL` of 'strict'.

These numbered points are referenced in the code, change them with care.

## Walkthrough

This walkthrough assumes you're using bash and have Java 1.7 installed.

### Producers

Jim, Fred and Sheila are Producers who want to send data.

1: Jim is a level 1 producer. He's got a DUL envelope to send, located in `demo/jim-dul-envelope.json`. He processes it, and saves to `demo/output/jim-dul-message.jwt`.

    $ PRODUCER_LEVEL=1 PRODUCER_ID='jim' java -jar demo/dultool.jar sign demo/jim-dul-envelope.json demo/output/jim-dul-message.jwt

    $ cat demo/output/jim-dul-message.jwt && echo
    eyJpc3MiOiJqaW0iLCJhbGciOiJub25lIn0.ewogICJ1dWlkIjogImU1ODNlY2EwLWZkZjQtNDVmZi04YzhlLTJjM2NlMTE5NmVhMSIsCiAgIm1lc3NhZ2UtdHlwZSI6ICJjb3VudGVyLWRvd25sb2FkIiwKICAic291cmNlLXRva2VuIjogImppbSIsCiAgImNhbGxiYWNrIjogImh0dHA6Ly9leGFtcGxlLmNvbS9jb3VudGVyLXNoYXJlLWNhbGxiYWNrIiwKICAibWVzc2FnZSI6IHsKICAgICJlbGVtZW50LTEiOiAiZm9vIiwKICAgICJlbGVtZW50LTIiOiAiYmFyIgogIH0gIAp9.

2: Fred is a level 2 producer. He's got a DUL envelope to send, located in `demo/fred-dul-envelope.json`. He processes it, and saves to `demo/output/fred-dul-message.jwt`.

    $ PRODUCER_LEVEL=2 PRODUCER_ID='fred' java -jar demo/dultool.jar sign demo/fred-dul-envelope.json demo/output/fred-dul-message.jwt

    $ cat demo/output/fred-dul-message.jwt && echo
    eyJpc3MiOiJmcmVkIiwiYWxnIjoiSFMyNTYifQ.ewogICJ1dWlkIjogImU1ODNlY2EwLWZkZjQtNDVmZi04YzhlLTJjM2NlMTE5NmVhMiIsCiAgIm1lc3NhZ2UtdHlwZSI6ICJjb3VudGVyLWRvd25sb2FkIiwKICAic291cmNlLXRva2VuIjogImZyZWQiLAogICJjYWxsYmFjayI6ICJodHRwOi8vZXhhbXBsZS5jb20vY291bnRlci1zaGFyZS1jYWxsYmFjayIsCiAgIm1lc3NhZ2UiOiB7CiAgICAiZWxlbWVudC0xIjogImZvbyIsCiAgICAiZWxlbWVudC0yIjogImJhciIKICB9ICAKfQ.-BZmBt1kRrVYTnQzTX-5HXSPUnEKinwPGUxFusbxQOM

3: Sheila is a level 3 producer. She's got a DUL envelope to send, lcoated in `demo/sheila-dul-envelope.json`. She has created a public/private key JWK and placed it in `demo/sheilas-private-jwk.json`. She's sent ito Crossref, who sent her back a JKU URL of `https://dul-token.crossref.org/tokens/jwk/example-provider.json`.

    $ PRODUCER_LEVEL=3 PRODUCER_ID='sheila' JKU_URL='https://dul-token.crossref.org/tokens/jwk/sheila/example.json' JWK='demo/sheilas-private-jwk.json' java -jar demo/dultool.jar sign demo/sheila-dul-envelope.json demo/output/sheila-dul-message.jwt

    $ cat demo/output/sheila-dul-message.jwt && echo
    eyJpc3MiOiJzaGVpbGEiLCJhbGciOiJSUzI1NiIsImprdSI6Imh0dHBzOlwvXC9kdWwtdG9rZW4uY3Jvc3NyZWYub3JnXC90b2tlbnNcL2p3a1wvc2hlaWxhXC9leGFtcGxlLmpzb24ifQ.ewogICJ1dWlkIjogImU1ODNlY2EwLWZkZjQtNDVmZi04YzhlLTJjM2NlMTE5NmVhMyIsCiAgIm1lc3NhZ2UtdHlwZSI6ICJjb3VudGVyLWRvd25sb2FkIiwKICAic291cmNlLXRva2VuIjogInNoZWxpYSIsCiAgImNhbGxiYWNrIjogImh0dHA6Ly9leGFtcGxlLmNvbS9jb3VudGVyLXNoYXJlLWNhbGxiYWNrIiwKICAibWVzc2FnZSI6IHsKICAgICJlbGVtZW50LTEiOiAiZm9vIiwKICAgICJlbGVtZW50LTIiOiAiYmFyIgogIH0gIAp9.iTfJ-nmw11mOTtUvCfMF3jPhGytx07pbsQ9n4tBFPeFI8oZ3AbWixudphWV5SKPsLE6tCFvcpbq9qWU5eshdfHoY6DQwiZZLrJXj9g1BfCTmO08cBMN4i2ap_8bd-tVpG13TAyb79WeA3ApuVh_gA_zlXoTmRpJQVyza4cratS2XtET5xb2SsF-ooWzOWs6WoeQTd1MNnD3BoXGI62FNHRvuXZXqVNzP_PX9A3k84vKiVlu7fS7w06XusggCnaNM64lo17RraI0Y-6rINLC-tgvYkPzz3u7vrhI1VZEfGj8-vFnrKcYBLRU8t1TyT7ChCtDMxjO49D4Iageukc5B5w

As level 3 is the default, she could also have typed:

    PRODUCER_ID='sheila' JKU_URL='https://dul-token.crossref.org/tokens/jwk/sheila/example.json' JWK='demo/sheilas-private-jwk.json' java -jar demo/dultool.jar sign demo/sheila-dul-envelope.json demo/output/sheila-dul-message.jwt

### Consumers

Zxc and Spqr and Wombat are Consumers and want to receive data. For the sake of example, Jim, Fred and Sheila send their messages to both Consumers. 

#### Relaxed consumer

Zcx is a relaxed 1 consumer. It reads the sent messages from Jim, Fred and Sheila. In relaxed mode, it can read everything, but can't validate any of the information.

1: Relaxed Zcx reads Jim:

    $ CONSUMER_LEVEL=relaxed java -jar demo/dultool.jar validate demo/output/jim-dul-message.jwt demo/output/jim-relaxed.json
    jim

    $ echo $? # exit code
    0

    $ cat demo/output/jim-relaxed.json && echo
    {
      "uuid": "e583eca0-fdf4-45ff-8c8e-2c3ce1196ea1",
      "message-type": "counter-download",
      "source-token": "jim",
      "callback": "http://example.com/counter-share-callback",
      "message": {
        "element-1": "foo",
        "element-2": "bar"
      }
    }

Notice that the PRODUCER_ID 'jim' was sent to STDOUT and we the input was retrieved from

2: Relaxed Zcx reads Fred:

    $ CONSUMER_LEVEL=relaxed java -jar demo/dultool.jar validate demo/output/fred-dul-message.jwt demo/output/fred-relaxed.json
    fred

    $ echo $? # exit code
    0

    $ cat demo/output/fred-relaxed.json && echo
    {
      "uuid": "e583eca0-fdf4-45ff-8c8e-2c3ce1196ea2",
      "message-type": "counter-download",
      "source-token": "fred",
      "callback": "http://example.com/counter-share-callback",
      "message": {
        "element-1": "foo",
        "element-2": "bar"
      }
    }
    
3: Relaxed Zcx reads Sheila:

    $ CONSUMER_LEVEL=relaxed java -jar demo/dultool.jar validate demo/output/sheila-dul-message.jwt demo/output/sheila-relaxed.json
    sheila

    $ echo $? # exit code
    0

    $ cat  demo/output/sheila-relaxed.json && echo
    {
      "uuid": "e583eca0-fdf4-45ff-8c8e-2c3ce1196ea3",
      "message-type": "counter-download",
      "source-token": "shelia",
      "callback": "http://example.com/counter-share-callback",
      "message": {
        "element-1": "foo",
        "element-2": "bar"
      }
    }

#### Strict consumer

Spqr is a strict consumer. It can trust the output.

1: Strict Spqr reads Jim:

    $ CONSUMER_LEVEL=strict java -jar demo/dultool.jar validate demo/output/jim-dul-message.jwt demo/output/jim-strict.json
    Error: Strict mode does not allow the algorithm: none

    $ echo $? # exit code
    1 

    $ cat demo/output/jim-strict.json && echo

Because Jim uses Level 1 and therefore no information can be verified, the input can't be parsed. The non-zero exit code indicates an operation.

2: Strict Spqr reads Fred. The output and the sender ID are both written to STDOUT because of the `-` output file option. In real operation they could be sent to different places.

    CONSUMER_LEVEL=strict java -jar demo/dultool.jar validate demo/output/fred-dul-message.jwt demo/output/fred-strict.json
    fred

    $ echo $? # exit code
    0

    $ cat demo/output/fred-strict.json && echo
    {
      "uuid": "e583eca0-fdf4-45ff-8c8e-2c3ce1196ea2",
      "message-type": "counter-download",
      "source-token": "fred",
      "callback": "http://example.com/counter-share-callback",
      "message": {
        "element-1": "foo",
        "element-2": "bar"
      }
    }

Because Fred used Level 2 (HMAC), the output ingegrity could be verified ok.

3: Strict Spqr reads Sheila.

    $ CONSUMER_LEVEL=strict java -jar demo/dultool.jar validate demo/output/sheila-dul-message.jwt demo/output/output/sheila-strict.json
    shiela

    $ echo $? # exit code
    0

    $ cat demo/output/sheila-strict.json && echo
    {
      "uuid": "e583eca0-fdf4-45ff-8c8e-2c3ce1196ea3",
      "message-type": "counter-download",
      "source-token": "shelia",
      "callback": "http://example.com/counter-share-callback",
      "message": {
        "element-1": "foo",
        "element-2": "bar"
      }
    }

Because Sheila used Level 3 (RSA), the output integrity can be verified, as can the identity of the sender.

## To build

  mvn assembly:assembly -DdescriptorId=jar-with-dependencies
  cp target/dultool-1.0-SNAPSHOT-jar-with-dependencies.jar demo/dultool.jar
