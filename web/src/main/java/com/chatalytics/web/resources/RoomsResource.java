package com.chatalytics.web.resources;

import com.chatalytics.compute.chat.dao.ChatAPIFactory;
import com.chatalytics.compute.chat.dao.IChatApiDAO;
import com.chatalytics.core.config.ChatAlyticsConfig;
import com.chatalytics.core.model.data.Room;
import com.chatalytics.web.constant.WebConstants;
import com.google.common.annotations.VisibleForTesting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import static com.chatalytics.web.constant.WebConstants.ROOM;

/**
 * REST endpoint for ChatAlytics rooms
 */
@Path(RoomsResource.ROOM_ENDPOINT)
public class RoomsResource {

    public static final String ROOM_ENDPOINT = WebConstants.API_PATH + "rooms";
    private static final Logger LOG = LoggerFactory.getLogger(RoomsResource.class);

    private final IChatApiDAO chatApiDao;

    public RoomsResource(ChatAlyticsConfig config) {
        this(ChatAPIFactory.getChatApiDao(config));
    }

    @VisibleForTesting
    protected RoomsResource(IChatApiDAO chatApiDao) {
        this.chatApiDao = chatApiDao;
    }

    /**
     * @return A map of room names to Room objects
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Room> getRooms() {
        LOG.debug("Got a call to get rooms");
        return chatApiDao.getRooms().values().stream()
                         .collect(Collectors.toMap(Room::getName,
                                                   Function.identity()));
    }

    /**
     * Returns a {@link Room} with the given <code>roomName</code>
     *
     * @param roomName
     *            The room name of the room to be returned
     * @return A {@link Room} with the given <code>roomName</code>
     */
    @GET
    @Path("room")
    @Produces(MediaType.APPLICATION_JSON)
    public Room getRoom(@QueryParam(ROOM) String roomName) {
        Map<String, Room> rooms = getRooms();
        return rooms.get(roomName);
    }

}
