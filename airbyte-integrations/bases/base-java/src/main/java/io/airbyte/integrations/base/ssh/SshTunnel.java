/*
 * MIT License
 *
 * Copyright (c) 2020 Airbyte
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.airbyte.integrations.base.ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import io.airbyte.commons.functional.CheckedConsumer;
import io.airbyte.commons.functional.CheckedFunction;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.string.Strings;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.util.List;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// todo (cgardens) - this needs unit tests. it is currently tested transitively via source postgres
// integration tests.
/**
 * Encapsulates the connection configuration for an ssh tunnel port forward through a proxy/bastion
 * host plus the remote host and remote port to forward to a specified local port.
 */
public class SshTunnel implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(SshTunnel.class);

  public enum Method {
    NO_TUNNEL,
    SSH_PASSWORD_AUTH,
    SSH_KEY_AUTH
  }

  public static final int TIMEOUT_MILLIS = 15000; // 15 seconds

  private final JsonNode config;
  private final List<String> hostKey;
  private final List<String> portKey;

  private final Method method;
  private final String host;
  private final int tunnelSshPort;
  private final String user;
  private final String sshKey;
  private final String tunnelUserPassword;
  private final String remoteDatabaseHost;
  private final int remoteDatabasePort;
  private int tunnelDatabasePort;

  private SshClient sshclient;
  private ClientSession tunnelSession;

  public SshTunnel(final JsonNode config,
                   final List<String> hostKey,
                   final List<String> portKey,
                   final Method method,
                   final String host,
                   final int tunnelSshPort,
                   final String user,
                   final String sshKey,
                   final String tunnelUserPassword,
                   final String remoteDatabaseHost,
                   final int remoteDatabasePort) {
    this.config = config;
    this.hostKey = hostKey;
    this.portKey = portKey;

    Preconditions.checkNotNull(method);
    this.method = method;

    if (method.equals(Method.NO_TUNNEL)) {
      this.host = null;
      this.tunnelSshPort = 0;
      this.user = null;
      this.sshKey = null;
      this.tunnelUserPassword = null;
      this.remoteDatabaseHost = null;
      this.remoteDatabasePort = 0;
    } else {
      Preconditions.checkNotNull(host);
      Preconditions.checkArgument(tunnelSshPort > 0);
      Preconditions.checkNotNull(user);
      if (method.equals(Method.SSH_KEY_AUTH)) {
        Preconditions.checkNotNull(sshKey);
      }
      if (method.equals(Method.SSH_PASSWORD_AUTH)) {
        Preconditions.checkNotNull(tunnelUserPassword);
      }
      Preconditions.checkNotNull(remoteDatabaseHost);
      Preconditions.checkArgument(remoteDatabasePort > 0);

      this.host = host;
      this.tunnelSshPort = tunnelSshPort;
      this.user = user;
      this.sshKey = sshKey;
      this.tunnelUserPassword = tunnelUserPassword;
      this.remoteDatabaseHost = remoteDatabaseHost;
      this.remoteDatabasePort = remoteDatabasePort;

      this.sshclient = createClient();
      this.tunnelSession = openTunnel(sshclient);
    }
  }

  public JsonNode getOriginalConfig() {
    return config;
  }

  public JsonNode getConfigInTunnel() {
    final JsonNode clone = Jsons.clone(config);
    Jsons.replaceNestedString(clone, hostKey, SshdSocketAddress.LOCALHOST_ADDRESS.getHostName());
    Jsons.replaceNestedInt(clone, portKey, tunnelDatabasePort);
    return clone;
  }

  // /**
  // * Finds a free port on the machine. As soon as this method returns, it is possible for process to
  // bind to this port. Thus it only gives a guarantee that at the time
  // */
  // private static int findFreePort() {
  // // finds an available port.
  // try (final var socket = new ServerSocket(0)) {
  // return socket.getLocalPort();
  // } catch (final IOException e) {
  // throw new RuntimeException(e);
  // }
  // }

  public static SshTunnel getInstance(final JsonNode config, final List<String> hostKey, final List<String> portKey) {
    final Method tunnelMethod = Jsons.getOptional(config, "tunnel_method", "tunnel_method")
        .map(method -> Method.valueOf(method.asText().trim()))
        .orElse(Method.NO_TUNNEL);
    LOGGER.info("Starting connection with method: {}", tunnelMethod);

    // final int localPort = findFreePort();

    return new SshTunnel(
        config,
        hostKey,
        portKey,
        tunnelMethod,
        Strings.safeTrim(Jsons.getStringOrNull(config, "tunnel_method", "tunnel_host")),
        Jsons.getIntOrZero(config, "tunnel_method", "tunnel_ssh_port"),
        Strings.safeTrim(Jsons.getStringOrNull(config, "tunnel_method", "tunnel_username")),
        Strings.safeTrim(Jsons.getStringOrNull(config, "tunnel_method", "tunnel_user_ssh_key")),
        Strings.safeTrim(Jsons.getStringOrNull(config, "tunnel_method", "tunnel_userpass")),
        Strings.safeTrim(Jsons.getStringOrNull(config, hostKey)),
        Jsons.getIntOrZero(config, portKey));
  }

  public static void sshWrap(final JsonNode config,
                             final List<String> hostKey,
                             final List<String> portKey,
                             final CheckedConsumer<JsonNode, Exception> wrapped)
      throws Exception {
    sshWrap(config, hostKey, portKey, (configInTunnel) -> {
      wrapped.accept(configInTunnel);
      return null;
    });
  }

  public static <T> T sshWrap(final JsonNode config,
                              final List<String> hostKey,
                              final List<String> portKey,
                              final CheckedFunction<JsonNode, T, Exception> wrapped)
      throws Exception {
    try (final SshTunnel sshTunnel = SshTunnel.getInstance(config, hostKey, portKey)) {
      return wrapped.apply(sshTunnel.getConfigInTunnel());
    }
  }

  /**
   * Closes a tunnel if one was open, and otherwise doesn't do anything (safe to run).
   */
  @Override
  public void close() {
    try {
      if (tunnelSession != null) {
        tunnelSession.close();
        tunnelSession = null;
      }
      if (sshclient != null) {
        sshclient.stop();
        sshclient = null;
      }
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * From the RSA format private key string, use bouncycastle to deserialize the key pair, reconstruct
   * the keys from the key info, and return the key pair for use in authentication.
   */
  private KeyPair getPrivateKeyPair() throws IOException {
    final PEMParser pemParser = new PEMParser(new StringReader(sshKey));
    final PEMKeyPair keypair = (PEMKeyPair) pemParser.readObject();
    final JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
    return new KeyPair(
        converter.getPublicKey(SubjectPublicKeyInfo.getInstance(keypair.getPublicKeyInfo())),
        converter.getPrivateKey(keypair.getPrivateKeyInfo()));
  }

  /**
   * Generates a new ssh client and returns it, with forwarding set to accept all types; use this
   * before opening a tunnel.
   */
  private SshClient createClient() {
    java.security.Security.addProvider(
        new org.bouncycastle.jce.provider.BouncyCastleProvider());
    final SshClient client = SshClient.setUpDefaultClient();
    client.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
    client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
    return client;
  }

  /**
   * Starts an ssh session; wrap this in a try-finally and use closeTunnel() to close it.
   */
  private ClientSession openTunnel(final SshClient client) {
    try {
      client.start();
      final ClientSession session = client.connect(
          user.trim(),
          host.trim(),
          tunnelSshPort)
          .verify(TIMEOUT_MILLIS)
          .getSession();
      if (method.equals(Method.SSH_KEY_AUTH)) {
        session.addPublicKeyIdentity(getPrivateKeyPair());
      }
      if (method.equals(Method.SSH_PASSWORD_AUTH)) {
        session.addPasswordIdentity(tunnelUserPassword);
      }

      session.auth().verify(TIMEOUT_MILLIS);
      final SshdSocketAddress address = session.startLocalPortForwarding(
          // entering 0 lets the OS pick a free port for us.
          new SshdSocketAddress(InetSocketAddress.createUnresolved(SshdSocketAddress.LOCALHOST_ADDRESS.getHostName(), 0)),
          new SshdSocketAddress(remoteDatabaseHost, remoteDatabasePort));

      // discover the port that the OS picked and remember it so that we can use it when we try to connect
      // later.
      tunnelDatabasePort = address.getPort();

      LOGGER.info("Established tunneling session.  Port forwarding started on " + address.toInetSocketAddress());
      return session;
    } catch (final IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public String toString() {
    return "SSHTunnel{" +
        "method=" + method +
        ", host='" + host + '\'' +
        ", tunnelSshPort='" + tunnelSshPort + '\'' +
        ", user='" + user + '\'' +
        ", remoteDatabaseHost='" + remoteDatabaseHost + '\'' +
        ", remoteDatabasePort='" + remoteDatabasePort + '\'' +
        ", tunnelDatabasePort='" + tunnelDatabasePort + '\'' +
        ", sshclient=" + sshclient +
        ", tunnelSession=" + tunnelSession +
        '}';
  }

}
