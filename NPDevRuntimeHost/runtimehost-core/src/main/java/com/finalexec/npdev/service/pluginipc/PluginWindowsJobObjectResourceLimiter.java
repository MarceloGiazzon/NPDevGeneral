package com.finalexec.npdev.service.pluginipc;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Windows Job Objects resource limiting (SEC-3 Model B step 4, design doc section 3): {@code
 * CreateJobObject} + {@code SetInformationJobObject} (memory ceiling and CPU rate hard cap) + {@code
 * AssignProcessToJobObject}, assigning the already-spawned child (there is no "start already inside a
 * job" primitive on Windows short of a suspended-process trick this design doesn't need).
 *
 * <p>Deliberately a self-contained, hand-declared kernel32 binding (five exports, four struct shapes
 * copied from {@code winnt.h}) rather than a dependency on {@code jna-platform}'s own {@code WinNT}/
 * {@code Kernel32} mappings -- keeps this class independently verifiable against the real OS API instead
 * of trusting an unfamiliar library's exact struct layout. 64-bit JVMs only (this platform's own
 * toolchain pin, Java 17/21) -- x64 has one uniform calling convention, so there is no stdcall/cdecl
 * distinction to declare the way a 32-bit binding would need.</p>
 *
 * <p>{@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE} is set alongside the memory ceiling: when the LAST open
 * handle to a job object closes, every process still in it is terminated. Holding the job handle for the
 * child's lifetime and closing it in {@link PluginIpcChildProcess#close()} means an abrupt host death
 * (handle closed by the OS without this code running) also reaps the child -- a bonus beyond the ceiling
 * itself, not something callers need to arrange separately.</p>
 */
final class PluginWindowsJobObjectResourceLimiter implements PluginProcessResourceLimiter {

    private static final Logger LOG = Logger.getLogger(PluginWindowsJobObjectResourceLimiter.class.getName());

    private static final int JOB_OBJECT_LIMIT_PROCESS_MEMORY = 0x00000100;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    private static final int JOB_OBJECT_CPU_RATE_CONTROL_ENABLE = 0x1;
    private static final int JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP = 0x4;
    private static final int JOBOBJECT_EXTENDED_LIMIT_INFORMATION_CLASS = 9;
    private static final int JOBOBJECT_CPU_RATE_CONTROL_INFORMATION_CLASS = 15;
    private static final int PROCESS_TERMINATE = 0x0001;
    private static final int PROCESS_SET_QUOTA = 0x0100;

    private final boolean available;

    PluginWindowsJobObjectResourceLimiter() {
        this.available = probe();
    }

    private static boolean probe() {
        try {
            Pointer job = Kernel32.INSTANCE.CreateJobObjectW(null, null);
            if (job == null) {
                return false;
            }
            Kernel32.INSTANCE.CloseHandle(job);
            return true;
        } catch (UnsatisfiedLinkError | RuntimeException probeFailure) {
            return false;
        }
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public ResourceLimitAttachment attachAfterStart(Process process, PluginProcessResourceLimits limits) {
        if (!available || limits.isEmpty()) {
            return ResourceLimitAttachment.NONE;
        }
        long pid = process.pid();
        Pointer job = Kernel32.INSTANCE.CreateJobObjectW(null, null);
        if (job == null) {
            LOG.log(Level.WARNING, "CreateJobObjectW failed (GetLastError={0}) -- plugin child process pid={1} "
                    + "is running WITHOUT a resource ceiling.", new Object[]{Kernel32.INSTANCE.GetLastError(), pid});
            return ResourceLimitAttachment.NONE;
        }
        configureLimits(job, limits, pid);
        Pointer processHandle = Kernel32.INSTANCE.OpenProcess(PROCESS_TERMINATE | PROCESS_SET_QUOTA, false, (int) pid);
        if (processHandle == null || !Kernel32.INSTANCE.AssignProcessToJobObject(job, processHandle)) {
            LOG.log(Level.WARNING, "Failed to assign plugin child process pid={0} to a resource-limited Job "
                    + "Object (GetLastError={1}) -- it is running WITHOUT the configured memory/CPU ceiling.",
                    new Object[]{pid, Kernel32.INSTANCE.GetLastError()});
        }
        if (processHandle != null) {
            Kernel32.INSTANCE.CloseHandle(processHandle);
        }
        return () -> Kernel32.INSTANCE.CloseHandle(job);
    }

    /** Best-effort per dimension: a failure on one (e.g. CPU rate control unsupported pre-Windows 8) must
     * not prevent the other from taking effect. */
    private static void configureLimits(Pointer job, PluginProcessResourceLimits limits, long pid) {
        if (limits.memoryLimitMb() != null) {
            JOBOBJECT_EXTENDED_LIMIT_INFORMATION info = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
            info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_PROCESS_MEMORY | JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            info.ProcessMemoryLimit = limits.memoryLimitMb() * 1024L * 1024L;
            info.write();
            if (!Kernel32.INSTANCE.SetInformationJobObject(job, JOBOBJECT_EXTENDED_LIMIT_INFORMATION_CLASS, info, info.size())) {
                LOG.log(Level.WARNING, "SetInformationJobObject(memory) failed for plugin child pid={0} "
                        + "(GetLastError={1})", new Object[]{pid, Kernel32.INSTANCE.GetLastError()});
            }
        }
        if (limits.cpuRatePercent() != null) {
            JOBOBJECT_CPU_RATE_CONTROL_INFORMATION cpu = new JOBOBJECT_CPU_RATE_CONTROL_INFORMATION();
            cpu.ControlFlags = JOB_OBJECT_CPU_RATE_CONTROL_ENABLE | JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP;
            cpu.CpuRate = limits.cpuRatePercent() * 100; // units of 1/10000 of one percent
            cpu.write();
            if (!Kernel32.INSTANCE.SetInformationJobObject(job, JOBOBJECT_CPU_RATE_CONTROL_INFORMATION_CLASS, cpu, cpu.size())) {
                LOG.log(Level.WARNING, "SetInformationJobObject(cpuRate) failed for plugin child pid={0} "
                        + "(GetLastError={1})", new Object[]{pid, Kernel32.INSTANCE.GetLastError()});
            }
        }
    }

    private interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        Pointer CreateJobObjectW(Pointer lpJobAttributes, Pointer lpName);

        boolean AssignProcessToJobObject(Pointer hJob, Pointer hProcess);

        boolean SetInformationJobObject(Pointer hJob, int jobObjectInfoClass, Structure lpJobObjectInfo, int cbJobObjectInfoLength);

        Pointer OpenProcess(int dwDesiredAccess, boolean bInheritHandle, int dwProcessId);

        boolean CloseHandle(Pointer hObject);

        int GetLastError();
    }

    @Structure.FieldOrder({
            "PerProcessUserTimeLimit", "PerJobUserTimeLimit", "LimitFlags", "MinimumWorkingSetSize",
            "MaximumWorkingSetSize", "ActiveProcessLimit", "Affinity", "PriorityClass", "SchedulingClass"
    })
    public static final class JOBOBJECT_BASIC_LIMIT_INFORMATION extends Structure {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public int LimitFlags;
        public long MinimumWorkingSetSize;
        public long MaximumWorkingSetSize;
        public int ActiveProcessLimit;
        public long Affinity;
        public int PriorityClass;
        public int SchedulingClass;
    }

    @Structure.FieldOrder({
            "ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
            "ReadTransferCount", "WriteTransferCount", "OtherTransferCount"
    })
    public static final class IO_COUNTERS extends Structure {
        public long ReadOperationCount;
        public long WriteOperationCount;
        public long OtherOperationCount;
        public long ReadTransferCount;
        public long WriteTransferCount;
        public long OtherTransferCount;
    }

    @Structure.FieldOrder({
            "BasicLimitInformation", "IoInfo", "ProcessMemoryLimit", "JobMemoryLimit",
            "PeakProcessMemoryUsed", "PeakJobMemoryUsed"
    })
    public static final class JOBOBJECT_EXTENDED_LIMIT_INFORMATION extends Structure {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation = new JOBOBJECT_BASIC_LIMIT_INFORMATION();
        public IO_COUNTERS IoInfo = new IO_COUNTERS();
        public long ProcessMemoryLimit;
        public long JobMemoryLimit;
        public long PeakProcessMemoryUsed;
        public long PeakJobMemoryUsed;
    }

    @Structure.FieldOrder({"ControlFlags", "CpuRate"})
    public static final class JOBOBJECT_CPU_RATE_CONTROL_INFORMATION extends Structure {
        public int ControlFlags;
        public int CpuRate;
    }
}
