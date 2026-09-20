import de.chrisliebaer.salvage.SalvageService;
import de.chrisliebaer.salvage.entity.SalvageConfiguration;
import de.chrisliebaer.salvage.entity.SalvageTide;
import com.github.dockerjava.api.DockerClient;
import java.time.Instant;

// Run the unchanged production orchestration immediately, avoiding cron delays.
class ReviewRunner {
  public static void main(String[] args) throws Exception {
    var clientMethod = SalvageService.class.getDeclaredMethod("createDefaultClient");
    clientMethod.setAccessible(true);
    try (var client = (DockerClient) clientMethod.invoke(null)) {
      var roots = client.listContainersCmd().withShowAll(true)
          .withLabelFilter(java.util.List.of("salvage.root")).exec();
      if (roots.size() != 1) throw new IllegalStateException("Expected one isolated review root");
      var id = roots.getFirst().getId();
      var config = SalvageConfiguration.fromContainerInspect(client.inspectContainerCmd(id).exec());
      var service = new SalvageService();
      for (var name : java.util.List.of("configuration", "ownContainerId")) {
        var field = SalvageService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, name.equals("configuration") ? config : id);
      }
      var method = SalvageService.class.getDeclaredMethod("tideExceptionWrapped", SalvageTide.class, Instant.class);
      method.setAccessible(true);
      for (var tide : config.tides()) method.invoke(service, tide, Instant.now());
    }
  }
}
