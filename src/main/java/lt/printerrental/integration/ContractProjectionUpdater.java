package lt.printerrental.integration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractProjectionUpdater {
  private final ContractSnapshotStore store;

  public ContractProjectionUpdater(ContractSnapshotStore store) {
    this.store = store;
  }

  @Transactional
  public void receive(ContractSnapshot snapshot) {
    store.receive(snapshot);
  }
}
