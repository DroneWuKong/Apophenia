using Toybox.Lang;

// A single in-flight prefix. New observations can only append, so completion
// removes exactly the transmitted batch, never observations added afterward.
class PendingEvents {
    var events as Lang.Array;
    var sentCount = 0;

    function initialize(saved) {
        events = [];
        if (saved instanceof Lang.Array) { events = saved; }
    }

    function append(packet) {
        if (events.size() >= 10) { return false; }
        events.add(packet);
        return true;
    }

    function begin() {
        if (sentCount != 0 || events.size() == 0) { return null; }
        sentCount = events.size();
        return events.slice(0, sentCount);
    }

    function complete() {
        events = events.slice(sentCount, null);
        sentCount = 0;
    }

    function failed() { sentCount = 0; }
}

(:test)
function preservesTapsDuringSend(logger) {
    var queue = new PendingEvents([1, 2]);
    var batch = queue.begin();
    queue.append(3);
    var overlapping = queue.begin();
    queue.complete();
    return batch.size() == 2 && overlapping == null &&
        queue.events.size() == 1 && queue.events[0] == 3;
}

(:test)
function failedSendRetainsEverything(logger) {
    var queue = new PendingEvents([1]);
    queue.begin();
    queue.append(2);
    queue.failed();
    var retry = queue.begin();
    return retry.size() == 2 && retry[0] == 1 && retry[1] == 2;
}

(:test)
function fullQueueDoesNotEvictUnsentEvents(logger) {
    var queue = new PendingEvents([0, 1, 2, 3, 4, 5, 6, 7, 8, 9]);
    queue.begin();
    var accepted = queue.append(10);
    return !accepted && queue.events.size() == 10 && queue.events[0] == 0;
}

(:test)
function emptyAndRestoredQueues(logger) {
    var empty = new PendingEvents(null);
    var restored = new PendingEvents([1, 2]);
    restored.begin();
    restored.complete();
    return empty.begin() == null && restored.events.size() == 0;
}
