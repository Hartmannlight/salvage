import com.github.dockerjava.api.DockerClient;
import de.chrisliebaer.salvage.SalvageService;
import de.chrisliebaer.salvage.StateTransaction;
import de.chrisliebaer.salvage.entity.SalvageContainer;
import java.util.*;

// Exercise a preparation failure's AutoCloseable rollback on real test containers.
class RollbackProbe {
  public static void main(String[] args) throws Exception {
    var method=SalvageService.class.getDeclaredMethod("createDefaultClient");
    method.setAccessible(true);
    try (var docker=(DockerClient)method.invoke(null)) {
      var transaction=new StateTransaction(docker);
      for(var id:args) {
        var container=SalvageContainer.fromContainer(docker.inspectContainerCmd(id).exec(),Map.of());
        transaction.prepare(container);
      }
      try {transaction.close(); System.out.println("ROLLBACK_OK");}
      catch(Throwable t) {System.out.println("ROLLBACK_ERROR="+t);}
      for(var id:args)
        System.out.println(id+" running="+docker.inspectContainerCmd(id).exec().getState().getRunning());
    }
  }
}
