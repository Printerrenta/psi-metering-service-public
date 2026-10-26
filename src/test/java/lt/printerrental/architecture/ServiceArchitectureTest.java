package lt.printerrental.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ServiceArchitectureTest {
  static {
    com.tngtech.archunit.ArchConfiguration.get().setResolveMissingDependenciesFromClassPath(false);
  }

  private final com.tngtech.archunit.core.domain.JavaClasses classes =
      new ClassFileImporter().importPath(java.nio.file.Path.of("target/classes"));

  @Test
  void implementationStateIsPrivate() {
    // Every service has instance fields; records and JPA entities are covered too.
    fields().that().areNotStatic().should().bePrivate().check(classes);
  }

  @Test
  void controllersDoNotAccessPersistence() {
    noClasses()
        .that()
        .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
        .should()
        .dependOnClassesThat()
        .haveSimpleNameEndingWith("Repository")
        .check(classes);
    noClasses()
        .that()
        .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.data.repository..",
            "org.springframework.data.jpa..",
            "org.springframework.jdbc..",
            "jakarta.persistence..")
        .check(classes);
  }

  @Test
  void applicationServicesDoNotDependOnHttp() {
    noClasses()
        .that()
        .areAnnotatedWith("org.springframework.stereotype.Service")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework.web..", "org.springframework.http..")
        .check(classes);
    noClasses()
        .that()
        .areAnnotatedWith("org.springframework.stereotype.Service")
        .should()
        .dependOnClassesThat()
        .haveSimpleNameEndingWith("Controller")
        .check(classes);
  }

  @Test
  void noForeignServiceImplementationDependency() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "lt.printerrental.request..",
            "lt.printerrental.rental..",
            "lt.printerrental.storage..",
            "lt.printerrental.gateway..")
        .check(classes);
  }
}
