using System;
using System.Diagnostics;
using System.IO;

internal static class Program
{
    static int Main()
    {
        string appHome = Path.GetDirectoryName(Process.GetCurrentProcess().MainModule.FileName);
#if STOP
        string title = "Qihang OMS - Stop";
        string scriptName = "Stop-QihangOms.ps1";
#else
        string title = "Qihang OMS - Start";
        string scriptName = "Start-QihangOms.ps1";
#endif
        try { Console.Title = title; } catch { }

        string script = Path.Combine(appHome, "package", "windows", scriptName);
        if (!File.Exists(script))
        {
            Console.Error.WriteLine("incomplete package: " + script);
            Pause();
            return 1;
        }

        string powershell = Path.Combine(Environment.SystemDirectory, @"WindowsPowerShell\v1.0\powershell.exe");
        var psi = new ProcessStartInfo();
        psi.FileName = powershell;
        psi.Arguments = "-NoProfile -ExecutionPolicy Bypass -File \"" + script + "\"";
        psi.WorkingDirectory = appHome;
        psi.UseShellExecute = false;
        try
        {
            Process process = Process.Start(psi);
            process.WaitForExit();
            if (process.ExitCode != 0) Pause();
            return process.ExitCode;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine(ex.Message);
            Pause();
            return 1;
        }
    }

    static void Pause()
    {
        Console.WriteLine("Press any key to close...");
        try { Console.ReadKey(true); } catch { }
    }
}
