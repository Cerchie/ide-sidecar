package io.confluent.idesidecar.restapi.connections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import io.confluent.idesidecar.restapi.exceptions.ConnectionNotFoundException;
import io.confluent.idesidecar.restapi.exceptions.CreateConnectionException;
import io.confluent.idesidecar.restapi.exceptions.InvalidInputException;
import io.confluent.idesidecar.restapi.models.ConnectionSpec;
import io.confluent.idesidecar.restapi.models.ConnectionSpec.ConnectionType;
import io.confluent.idesidecar.restapi.util.UuidFactory;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ConnectionStateManagerTest {

  private static final String GENERATED_ID = "99a2b4ce-7a87-4dd2-b967-fe9f34fcbea4";

  @InjectMock
  UuidFactory idFactory;

  @Inject
  ConnectionStateManager manager;

  @BeforeEach
  void setUp() {
    manager.clearAllConnectionStates();
    when(idFactory.getRandomUuid()).thenReturn(GENERATED_ID);
  }

  @Test
  void shouldCreateConnectionWithSpecContainingIdAndNameAndType()
      throws ConnectionNotFoundException, CreateConnectionException {
    // When there is no existing connection with a specific ID
    assertConnectionNotFound("1");
    // Then creating a connection with a spec will succeed
    var expected = new ConnectionSpec("1", "Test", ConnectionType.LOCAL);
    var actual = manager.createConnectionState(expected).getSpec();
    assertEquals(expected.id(), actual.id());
    assertEquals(expected.name(), actual.name());
    assertEquals(expected.type(), actual.type());
    // and getting it again will return the same spec
    assertConnectionFound(actual);
  }

  @Test
  void shouldFailToFindConnectionWithUnknownId() {
    assertConnectionNotFound("unknown");
  }

  @Test
  void shouldCreateConnectionWithoutId() throws CreateConnectionException {
    // When creating a connection with a spec that has no ID
    var expected = new ConnectionSpec(null, "Test", ConnectionType.LOCAL);
    // Then the actual will contain the ID generated by the id factory
    var actual = manager.createConnectionState(expected).getSpec();
    assertEquals(GENERATED_ID, actual.id());
    assertEquals(expected.name(), actual.name());
    assertEquals(expected.type(), actual.type());
    // and getting it again will return the same spec
    assertConnectionFound(actual);
  }

  @Test
  void shouldFailToCreateConnectionWithExistingId() throws CreateConnectionException {
    // When the manager has a connection with a specific ID
    var existing = new ConnectionSpec("1", "Test Connection", ConnectionType.CCLOUD);
    manager.createConnectionState(existing);
    assertConnectionFound(existing);

    // Then attempting to recreate the same spec will fail
    assertThrows(CreateConnectionException.class, () -> manager.createConnectionState(existing));

    // and attempting to create a connection with the same ID (but different name and type)
    // will fail
    var duplicate = new ConnectionSpec("1", "Other name", ConnectionType.LOCAL);
    assertThrows(CreateConnectionException.class, () -> manager.createConnectionState(duplicate));
  }

  @Test
  void shouldFailToDeleteConnectionWithNonExistentId() {
    // When there is not connection with a specific ID
    var id = "unknown";
    assertConnectionNotFound(id);
    // Then trying to delete a connection with that ID will fail
    assertThrows(ConnectionNotFoundException.class, () -> manager.deleteConnectionState(id));
  }

  @Test
  void shouldReturnAllConnections() throws CreateConnectionException {
    // When adding multiple connections
    ConnectionSpec spec1 = new ConnectionSpec("1", "Test1", ConnectionType.CCLOUD);
    ConnectionSpec spec2 = new ConnectionSpec("2", "Test2", ConnectionType.CCLOUD);
    ConnectionSpec localConnectionSpec = new ConnectionSpec("3", "Test3", ConnectionType.LOCAL);
    manager.createConnectionState(spec1);
    manager.createConnectionState(spec2);
    manager.createConnectionState(localConnectionSpec);

    // Then all connections can be found individually
    assertConnectionFound(spec1);
    assertConnectionFound(spec2);
    assertConnectionFound(localConnectionSpec);

    // and all connections will be returned
    var specs = manager.getConnectionStates()
        .stream()
        .map(ConnectionState::getSpec)
        .toList();
    assertEquals(3, specs.size());
    assertTrue(specs.contains(spec1));
    assertTrue(specs.contains(spec2));
    assertTrue(specs.contains(localConnectionSpec));
  }

  @Test
  void shouldUpdateConnection()
      throws ConnectionNotFoundException, CreateConnectionException, InvalidInputException {
    // When a connection is added
    var original = new ConnectionSpec("1", "Original", ConnectionType.PLATFORM);
    ConnectionState connectionState = ConnectionStates.from(original, null);
    manager.createConnectionState(connectionState.getSpec());
    assertConnectionFound(original);

    // Then that spec's name can be updated
    var newName = original.name() + " Updated";
    var updatedSpec = new ConnectionSpec(original.id(), newName, original.type());
    manager.updateSpecForConnectionState("1", updatedSpec).subscribe().asCompletionStage().join();
    assertEquals(newName, manager.getConnectionSpec(original.id()).name());
  }

  @Test
  void shouldFailToUpdateConnectionThatDoesNotExist() {
    // When a connection is not found
    var id = "1";
    assertConnectionNotFound(id);

    // Then updating a spec with that ID will fail
    var newSpec = new ConnectionSpec(id, "Updated", ConnectionType.LOCAL);
    var exc = assertThrows(
        CompletionException.class,
        () -> manager.updateSpecForConnectionState(id, newSpec)
            .subscribe().asCompletionStage().join()
    );

    assertInstanceOf(ConnectionNotFoundException.class, exc.getCause());
  }

  @Test
  void getConnectionStateByInternalIdShouldLookupConnectionByInternalId()
      throws CreateConnectionException {

    // Create three connections
    var firstSpec = new ConnectionSpec("1", "Test1", ConnectionType.CCLOUD);
    var secondSpec = new ConnectionSpec("2", "Test2", ConnectionType.CCLOUD);
    var thirdSpec = new ConnectionSpec("3", "Test3", ConnectionType.CCLOUD);
    var firstConnection = manager.createConnectionState(firstSpec);
    var secondConnection = manager.createConnectionState(secondSpec);
    manager.createConnectionState(thirdSpec);

    // Lookup first connection by its internal ID
    assertEquals(
        firstConnection,
        manager.getConnectionStateByInternalId(firstConnection.getInternalId()));

    // Lookup second connection by its internal ID
    assertEquals(
        secondConnection,
        manager.getConnectionStateByInternalId(secondConnection.getInternalId()));
  }

  @Test
  void getConnectionStateByInternalIdShouldThrowErrorIfInternalIdIsUnknown() {
    assertThrows(
        ConnectionNotFoundException.class,
        () -> manager.getConnectionStateByInternalId("UNKNOWN_CONNECTION")
    );
  }

  @Test
  void getConnectionStateByInternalIdShouldRequireNonNullParameter() {
    assertThrows(
        NullPointerException.class,
        () -> manager.getConnectionStateByInternalId(null)
    );
  }

  void assertConnectionNotFound(String id) {
    assertThrows(ConnectionNotFoundException.class, () -> manager.getConnectionState(id));
    assertThrows(ConnectionNotFoundException.class, () -> manager.getConnectionSpec(id));
  }

  void assertConnectionFound(ConnectionSpec expected) {
    assertEquals(expected, manager.getConnectionState(expected.id()).getSpec());
    assertEquals(expected, manager.getConnectionSpec(expected.id()));
  }
}

