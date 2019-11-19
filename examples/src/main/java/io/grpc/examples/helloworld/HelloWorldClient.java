/*
 * Copyright 2015 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.examples.helloworld;

import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Token;
import com.newrelic.api.agent.Trace;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * A simple client that requests a greeting from the {@link HelloWorldServer}.
 */
public class HelloWorldClient {
  private static final Logger logger = Logger.getLogger(HelloWorldClient.class.getName());

  private final ManagedChannel channel;
  private final GreeterGrpc.GreeterBlockingStub blockingStub;

  /** Construct client connecting to HelloWorld server at {@code host:port}. */
  public HelloWorldClient(String host, int port) {
    this(ManagedChannelBuilder.forAddress(host, port)

        // Enable client side LB
        .defaultLoadBalancingPolicy("round_robin")

        .intercept(new NewRelicInterceptor())

        // Channels are secure by default (via SSL/TLS). For the example we disable TLS
        // to avoid
        // needing certificates.
        .usePlaintext().build());
  }

  /**
   * Construct client for accessing HelloWorld server using the existing channel.
   */
  HelloWorldClient(ManagedChannel channel) {
    this.channel = channel;
    blockingStub = GreeterGrpc.newBlockingStub(channel);
  }

  public void shutdown() throws InterruptedException {
    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  /** Say hello to server. */
  public void greet(String name, int count) {
    logger.info("Will try to greet " + name + " ...");
    HelloRequest request = HelloRequest.newBuilder().setName(name).build();
    HelloReply response;
    try {
      response = blockingStub.sayHello(request);
    } catch (StatusRuntimeException e) {
      logger.log(Level.WARNING, "RPC {0} failed: {1}", new Object[] { count, e.getStatus() });
      return;
    }
    logger.info("Greeting " + String.valueOf(count) + ": " + response.getMessage());
  }

  private static class NewRelicInterceptor implements ClientInterceptor {
    @Override
    @Trace(dispatcher = true)
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions, Channel next) {
      NewRelic.setTransactionName(null, method.getFullMethodName());
      final Token token = NewRelic.getAgent().getTransaction().getToken();
      return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
            @Override
            @Trace(async = true)
            public void onClose(Status status, Metadata trailers) {
              token.linkAndExpire();
              super.onClose(status, trailers);
            }
          }, headers);
        }
      };
    }
  }

  /**
   * Greet server. If provided, the first element of {@code args} is the name to
   * use in the greeting.
   */
  public static void main(String[] args) throws Exception {
    Thread.sleep(5000);

    // HelloWorldClient client = new HelloWorldClient("grpc.dounan.test", 50050);
    HelloWorldClient client = new HelloWorldClient("localhost", 50051);
    try {
      String user = "world";
      // Use the arg as the name to greet if provided
      if (args.length > 0) {
        user = args[0];
      }
      Scanner scanner = new Scanner(System.in);
      String result = "";
      int count = 1;
      while (result.equals("")) {
        client.greet(user, count);
        result = scanner.nextLine().trim();
        count++;
      }
      scanner.close();
    } finally {
      client.shutdown();
    }
  }
}
