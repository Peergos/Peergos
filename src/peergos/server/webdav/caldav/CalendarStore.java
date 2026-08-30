package peergos.server.webdav.caldav;

import peergos.shared.user.UserContext;
import peergos.shared.user.fs.FileWrapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The calendar data the web calendar app reads and writes, presented as flat collections.
 *
 * On disk a calendar is sharded by the event's own UTC month, with recurring events pulled
 * out into their own directory:
 * <pre>
 *   &lt;user&gt;/.apps/calendar/data/&lt;dir&gt;/&lt;year&gt;/&lt;month&gt;/&lt;uid&gt;.ics
 *   &lt;user&gt;/.apps/calendar/data/&lt;dir&gt;/recurring/&lt;uid&gt;.ics
 *   &lt;user&gt;/.apps/calendar/data/&lt;dir&gt;/tasks/&lt;uid&gt;.ics
 * </pre>
 * The tasks directory is the bridge's own: CalDAV has no separate home for task lists, so
 * a VTODO belongs to a calendar collection like an event does, but it has no month to be
 * sharded by and the web calendar app has no idea what to do with one.
 *
 * CalDAV wants one flat collection per calendar, so this class hides the sharding: it
 * lists every .ics under a calendar directory as a direct member, and files a new one
 * where the web app would have put it.
 */
public class CalendarStore extends AppDataStore {

    public static final String APP_NAME = "calendar";
    public static final String CALENDAR_INFO_FILENAME = "calendar.inf";
    public static final String RECURRING_DIR = "recurring";
    public static final String TASKS_DIR = "tasks";
    public static final String ICS_SUFFIX = ".ics";
    public static final String TASK_COMPONENT = "VTODO";

    public CalendarStore(UserContext context) {
        super(context, APP_NAME, "calendars", CALENDAR_INFO_FILENAME, ICS_SUFFIX,
                "default", "My Calendar", "#00a9ff");
    }

    @Override
    protected List<ObjectRef> readObjects(String directory) {
        List<ObjectRef> objects = new ArrayList<>();
        for (FileWrapper shard : children(collectionPath(directory))) {
            if (! shard.isDirectory())
                continue;
            if (shard.getName().equals(RECURRING_DIR) || shard.getName().equals(TASKS_DIR)) {
                collect(shard, shard.getName(), objects);
            } else if (isYear(shard.getName())) {
                for (FileWrapper month : children(shard)) {
                    if (month.isDirectory())
                        collect(month, shard.getName() + "/" + month.getName(), objects);
                }
            }
        }
        return objects;
    }

    @Override
    protected Optional<String> shardFor(byte[] content) {
        return shardFor(ICal.summarise(new String(content, StandardCharsets.UTF_8)));
    }

    /**
     * The shard an object belongs in, matching what the web calendar app writes: recurring
     * events live in their own directory, everything else under the UTC year and month of
     * its start, named 1-based and unpadded.
     *
     * Tasks are the exception. A VTODO has no month to be filed under - it may carry no
     * date at all - so it gets its own directory, which is also what keeps it away from
     * the web calendar app: that app only ever reads &lt;year&gt;/&lt;month&gt; and
     * recurring/ by name, so a task never reaches code that would try to draw it as an
     * event.
     *
     * Empty when the object has no readable start and neither recurs nor is a task, which
     * means there is nowhere to put it that a reader would ever look.
     */
    public static Optional<String> shardFor(ICal.Summary summary) {
        if (summary.componentType.filter(TASK_COMPONENT::equals).isPresent())
            return Optional.of(TASKS_DIR);
        if (summary.recurring)
            return Optional.of(RECURRING_DIR);
        return ICal.shard(summary).map(month -> month.getYear() + "/" + month.getMonthValue());
    }

    private static boolean isYear(String name) {
        return name.length() == 4 && name.chars().allMatch(Character::isDigit);
    }
}
