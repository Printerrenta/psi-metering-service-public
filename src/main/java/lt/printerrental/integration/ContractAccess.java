package lt.printerrental.integration;

import java.util.UUID;

public interface ContractAccess {
  ContractSnapshot get(UUID contractId);
}
