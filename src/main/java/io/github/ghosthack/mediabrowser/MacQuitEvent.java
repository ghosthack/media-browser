package io.github.ghosthack.mediabrowser;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Tells the system's quits from the user's on macOS. JavaFX answers every
 * request to terminate — the app menu's Quit (Cmd+Q), and the Apple Event
 * quit that logout, restart, shutdown, a script or another app sends — by
 * closing every window, so the window-close handler sees them all alike.
 * While that close runs (on the AppKit main thread, which is the JavaFX
 * thread on macOS), {@code NSAppleEventManager.currentAppleEvent} is the
 * {@code aevt/quit} event for a system quit and nil for the menu's.
 *
 * <p>Media Browser keeps serving the phone page through the user's Quit when
 * Settings say so (docs/web-mode.md), but must never hold up a logout or
 * restart: those really quit. No-op ({@code false}) off macOS or on any
 * failure — the safe answer then is "the user's quit".</p>
 */
final class MacQuitEvent {

    private MacQuitEvent() {}

    private static final int AEVT = 0x61657674;   // 'aevt', kCoreEventClass
    private static final int QUIT = 0x71756974;   // 'quit', kAEQuitApplication

    private static final AddressLayout ADDR = ValueLayout.ADDRESS;
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;

    /** Whether the quit being handled right now arrived as an Apple Event (not the app menu). */
    static boolean systemQuitInProgress() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return false;
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup objc = MacObjc.lookup();
            Linker linker = Linker.nativeLinker();
            MethodHandle getClass = linker.downcallHandle(objc.find("objc_getClass").orElseThrow(),
                    FunctionDescriptor.of(ADDR, ADDR));
            MethodHandle selRegister = linker.downcallHandle(objc.find("sel_registerName").orElseThrow(),
                    FunctionDescriptor.of(ADDR, ADDR));
            MethodHandle sendObj = linker.downcallHandle(objc.find("objc_msgSend").orElseThrow(),
                    FunctionDescriptor.of(ADDR, ADDR, ADDR));
            MethodHandle sendLong = linker.downcallHandle(objc.find("objc_msgSend").orElseThrow(),
                    FunctionDescriptor.of(I64, ADDR, ADDR));

            MemorySegment managerClass = (MemorySegment) getClass.invoke(arena.allocateFrom("NSAppleEventManager"));
            if (managerClass.address() == 0) return false;
            MemorySegment manager = (MemorySegment) sendObj.invoke(managerClass,
                    (MemorySegment) selRegister.invoke(arena.allocateFrom("sharedAppleEventManager")));
            if (manager.address() == 0) return false;
            MemorySegment event = (MemorySegment) sendObj.invoke(manager,
                    (MemorySegment) selRegister.invoke(arena.allocateFrom("currentAppleEvent")));
            if (event.address() == 0) return false;
            // FourCharCodes come back as 32-bit values; the register's upper half is not theirs.
            int eventClass = (int) (long) sendLong.invoke(event,
                    (MemorySegment) selRegister.invoke(arena.allocateFrom("eventClass")));
            int eventId = (int) (long) sendLong.invoke(event,
                    (MemorySegment) selRegister.invoke(arena.allocateFrom("eventID")));
            return eventClass == AEVT && eventId == QUIT;
        } catch (Throwable t) {
            System.err.println("[MacQuitEvent] could not read the current Apple Event: " + t);
            return false;
        }
    }
}
