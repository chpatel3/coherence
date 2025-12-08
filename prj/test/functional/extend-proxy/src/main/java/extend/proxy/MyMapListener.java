package extend.proxy;

import com.tangosol.util.MultiplexingMapListener;
import com.tangosol.util.MapEvent;

public class MyMapListener
        extends MultiplexingMapListener {

    @Override
    protected void onMapEvent(MapEvent mapEvent) {
        System.out.println("=================================");
        System.out.println("MapEventId == " + mapEvent.getId());
        System.out.println("key=" + mapEvent.getKey() + ", old=" + mapEvent.getOldValue() + ", new=" + mapEvent.getNewValue());
        System.out.println(mapEvent.toString());
        System.out.println("=================================");
    }

}
