package lt.printerrental.integration;

import java.util.*;

public interface ContractSnapshotStore {
  record Locked(ContractSnapshot snapshot, long version) {}

  Optional<ContractSnapshot> find(UUID contract);

  Locked lock(UUID contract);

  boolean receive(ContractSnapshot snapshot);

  long bump(UUID contract);

  long version(UUID contract);

  List<UUID> ids();
}
