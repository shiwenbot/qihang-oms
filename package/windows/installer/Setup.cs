using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

internal static class Program
{
    public const string Magic = "QHOMSFX1";
    public const string ProductName = "启航电商 OMS";
    public const string UninstallReg = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\QihangOMS";
    public const string LogName = "QihangOMS-setup.log";

    [STAThread]
    static int Main(string[] args)
    {
        Options opt = Options.Parse(args);
        try
        {
            if (opt.Silent)
            {
                if (opt.Uninstall) Engine.Uninstall(opt, null);
                else Engine.Install(opt, null);
                return 0;
            }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm(opt));
            return 0;
        }
        catch (Exception ex)
        {
            Engine.Log("ERROR " + ex);
            if (!opt.Silent) MessageBox.Show(ex.Message, ProductName, MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }
}

internal sealed class Options
{
    public bool Silent;
    public bool Uninstall;
    public bool DeleteData;
    public bool DesktopShortcut = true;
    public bool LaunchAfter = true;
    public string Dir;

    public static Options Parse(string[] args)
    {
        var o = new Options();
        string self = Process.GetCurrentProcess().MainModule.FileName;
        if (string.Equals(Path.GetFileNameWithoutExtension(self), "Uninstall-QihangOMS", StringComparison.OrdinalIgnoreCase))
            o.Uninstall = true;
        foreach (string raw in args)
        {
            if (string.IsNullOrEmpty(raw)) continue;
            string a = raw.Trim();
            string upper = a.ToUpperInvariant();
            if (upper == "/S" || upper == "/SILENT") o.Silent = true;
            else if (upper == "/UNINSTALL") o.Uninstall = true;
            else if (upper == "/DELETEDATA") o.DeleteData = true;
            else if (upper == "/NODESKTOP") o.DesktopShortcut = false;
            else if (upper == "/NOLAUNCH") o.LaunchAfter = false;
            else if (upper.StartsWith("/DIR=")) o.Dir = a.Substring(5).Trim('"');
            else if (upper.StartsWith("/D=")) o.Dir = a.Substring(3).Trim('"');
        }
        if (o.Silent) o.LaunchAfter = false;
        if (string.IsNullOrEmpty(o.Dir))
        {
            if (o.Uninstall)
            {
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(Program.UninstallReg))
                {
                    if (key != null) o.Dir = key.GetValue("InstallLocation") as string;
                }
            }
            if (string.IsNullOrEmpty(o.Dir))
                o.Dir = Directory.Exists("D:\\") ? @"D:\QihangOMS" : @"C:\QihangOMS";
        }
        return o;
    }
}

internal static class Engine
{
    public static void Log(string line)
    {
        try
        {
            string path = Path.Combine(Path.GetTempPath(), Program.LogName);
            File.AppendAllText(path, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " " + line + Environment.NewLine, Encoding.UTF8);
        }
        catch { }
    }

    public static string ValidateDir(string dir)
    {
        if (string.IsNullOrWhiteSpace(dir)) return "请选择安装目录";
        string full;
        try { full = Path.GetFullPath(dir.Trim()); }
        catch { return "安装路径无效"; }
        foreach (char c in full)
        {
            if (c > 0x7F) return "路径必须是纯英文（例如 D:\\QihangOMS）。MySQL 无法从中文路径启动。";
        }
        if (full.IndexOfAny(Path.GetInvalidPathChars()) >= 0) return "安装路径含有非法字符";
        return null;
    }

    public static bool LooksInstalled(string dir)
    {
        return File.Exists(Path.Combine(dir, "app", "oms.jar"))
            || File.Exists(Path.Combine(dir, "QihangOMS.exe"))
            || File.Exists(Path.Combine(dir, "Start-OMS.exe"))
            || File.Exists(Path.Combine(dir, "BUILD-INFO.txt"));
    }

    public static Payload FindPayload()
    {
        string self = Process.GetCurrentProcess().MainModule.FileName;
        using (FileStream fs = File.OpenRead(self))
        {
            if (fs.Length < 16) return null;
            fs.Seek(-16, SeekOrigin.End);
            var br = new BinaryReader(fs);
            long zipLen = br.ReadInt64();
            string magic = Encoding.ASCII.GetString(br.ReadBytes(8));
            if (magic != Program.Magic) return null;
            long zipOffset = fs.Length - 16 - zipLen;
            if (zipOffset < 0 || zipLen <= 0) return null;
            return new Payload { SelfPath = self, ZipOffset = zipOffset, ZipLength = zipLen };
        }
    }

    public static void Install(Options opt, Action<int, string> progress)
    {
        string err = ValidateDir(opt.Dir);
        if (err != null) throw new InvalidOperationException(err);
        string dest = Path.GetFullPath(opt.Dir.Trim());
        Payload payload = FindPayload();
        if (payload == null) throw new InvalidOperationException("安装包不完整，请重新下载 QihangOMS-Setup.exe");
        Log("install dest=" + dest + " zipOffset=" + payload.ZipOffset + " zipLen=" + payload.ZipLength);
        Report(progress, 1, "正在停止已运行的服务...");
        StopIfPresent(dest);
        Directory.CreateDirectory(dest);
        Report(progress, 5, "正在解压文件...");
        Extract(payload, dest, progress);
        Report(progress, 90, "正在创建快捷方式...");
        string hostExe = Path.Combine(dest, "QihangOMS.exe");
        if (!File.Exists(hostExe)) hostExe = Path.Combine(dest, "Start-OMS.exe");
        string uninstallExe = Path.Combine(dest, "Uninstall-QihangOMS.exe");
        CopyStub(payload, uninstallExe);
        TryDelete(StartMenuLnk("启动启航OMS"));
        TryDelete(StartMenuLnk("停止启航OMS"));
        TryDelete(DesktopLnk("启动启航OMS"));
        TryDelete(DesktopLnk("停止启航OMS"));
        CreateShortcut(StartMenuLnk(Program.ProductName), hostExe, dest, Program.ProductName);
        if (opt.DesktopShortcut)
            CreateShortcut(DesktopLnk(Program.ProductName), hostExe, dest, Program.ProductName);
        WriteUninstallKey(dest, uninstallExe, hostExe, payload.ZipLength);
        Report(progress, 100, "安装完成");
        Log("install ok");
        if (opt.LaunchAfter && File.Exists(hostExe))
        {
            Process.Start(new ProcessStartInfo(hostExe) { WorkingDirectory = dest, UseShellExecute = true });
        }
    }

    public static void Uninstall(Options opt, Action<int, string> progress)
    {
        string dest = string.IsNullOrEmpty(opt.Dir) ? null : Path.GetFullPath(opt.Dir.Trim());
        if (string.IsNullOrEmpty(dest) || !Directory.Exists(dest))
            throw new InvalidOperationException("未找到安装目录");
        Log("uninstall dest=" + dest + " deleteData=" + opt.DeleteData);
        Report(progress, 5, "正在停止服务...");
        StopIfPresent(dest);
        TryDelete(StartMenuLnk(Program.ProductName));
        TryDelete(StartMenuLnk("启动启航OMS"));
        TryDelete(StartMenuLnk("停止启航OMS"));
        TryDelete(DesktopLnk(Program.ProductName));
        TryDelete(DesktopLnk("启动启航OMS"));
        TryDelete(DesktopLnk("停止启航OMS"));
        TryDeleteDir(StartMenuDir());
        Report(progress, 40, "正在删除程序文件...");
        DeleteInstalledFiles(dest, opt.DeleteData);
        try { Registry.CurrentUser.DeleteSubKeyTree(Program.UninstallReg, false); } catch { }
        Report(progress, 100, "卸载完成");
        Log("uninstall ok");
    }

    static void Extract(Payload payload, string dest, Action<int, string> progress)
    {
        using (FileStream fs = File.OpenRead(payload.SelfPath))
        using (var slice = new SliceStream(fs, payload.ZipOffset, payload.ZipLength))
        using (var zip = new ZipArchive(slice, ZipArchiveMode.Read, true))
        {
            long total = 0;
            foreach (ZipArchiveEntry e in zip.Entries) total += Math.Max(e.Length, 0);
            if (total <= 0) total = 1;
            long done = 0;
            foreach (ZipArchiveEntry entry in zip.Entries)
            {
                string rel = NormalizeRel(entry.FullName);
                if (rel.Length == 0 || rel.IndexOf("..", StringComparison.Ordinal) >= 0) continue;
                bool isDir = entry.Name.Length == 0 || rel.EndsWith("\\");
                string destPath = Path.Combine(dest, rel);
                if (IsProtected(rel) && (isDir ? Directory.Exists(destPath) : File.Exists(destPath)))
                {
                    done += Math.Max(entry.Length, 0);
                    continue;
                }
                if (string.Equals(destPath, payload.SelfPath, StringComparison.OrdinalIgnoreCase)) continue;
                if (isDir)
                {
                    Directory.CreateDirectory(destPath);
                    continue;
                }
                Directory.CreateDirectory(Path.GetDirectoryName(destPath));
                ExtractFile(entry, destPath);
                done += Math.Max(entry.Length, 0);
                int pct = 5 + (int)(done * 80 / total);
                Report(progress, pct, "正在复制 " + rel);
            }
        }
    }

    static void ExtractFile(ZipArchiveEntry entry, string destPath)
    {
        const int tries = 5;
        for (int i = 0; i < tries; i++)
        {
            try
            {
                using (Stream src = entry.Open())
                using (FileStream dst = new FileStream(destPath, FileMode.Create, FileAccess.Write, FileShare.None))
                {
                    src.CopyTo(dst);
                }
                try { File.SetLastWriteTime(destPath, entry.LastWriteTime.DateTime); } catch { }
                return;
            }
            catch (IOException)
            {
                if (i == tries - 1) throw;
                Thread.Sleep(400);
            }
        }
    }

    static string NormalizeRel(string name)
    {
        string n = (name ?? "").Replace('/', '\\').Trim('\\');
        if (n.StartsWith("QihangOMS\\", StringComparison.OrdinalIgnoreCase)) n = n.Substring("QihangOMS\\".Length);
        return n;
    }

    static bool IsProtected(string rel)
    {
        string p = rel.Replace('/', '\\');
        return StartsWithPath(p, @"runtime\mysql\data")
            || StartsWithPath(p, "config")
            || StartsWithPath(p, "logs");
    }

    static bool StartsWithPath(string rel, string prefix)
    {
        if (rel.Equals(prefix, StringComparison.OrdinalIgnoreCase)) return true;
        return rel.StartsWith(prefix + "\\", StringComparison.OrdinalIgnoreCase);
    }

    static void CopyStub(Payload payload, string destExe)
    {
        using (FileStream src = File.OpenRead(payload.SelfPath))
        using (FileStream dst = File.Create(destExe))
        {
            byte[] buf = new byte[64 * 1024];
            long left = payload.ZipOffset;
            while (left > 0)
            {
                int n = src.Read(buf, 0, (int)Math.Min(buf.Length, left));
                if (n <= 0) break;
                dst.Write(buf, 0, n);
                left -= n;
            }
        }
    }

    static void StopIfPresent(string dest)
    {
        string ps1 = Path.Combine(dest, "package", "windows", "Stop-QihangOms.ps1");
        if (File.Exists(ps1))
        {
            try { RunPowershell("-NoProfile -ExecutionPolicy Bypass -File \"" + ps1 + "\"", 60000); }
            catch (Exception ex) { Log("stop failed: " + ex.Message); }
        }
        KillHost(dest);
    }

    static void KillHost(string dest)
    {
        string prefix = Path.GetFullPath(dest).TrimEnd('\\') + "\\";
        foreach (Process p in Process.GetProcessesByName("QihangOMS"))
        {
            try
            {
                string path = p.MainModule != null ? p.MainModule.FileName : null;
                if (path != null && path.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
                {
                    p.Kill();
                    p.WaitForExit(5000);
                }
            }
            catch { }
        }
    }

    static void RunPowershell(string arguments, int timeoutMs)
    {
        string powershell = Path.Combine(Environment.SystemDirectory, @"WindowsPowerShell\v1.0\powershell.exe");
        var psi = new ProcessStartInfo(powershell, arguments);
        psi.UseShellExecute = false;
        psi.CreateNoWindow = true;
        psi.WindowStyle = ProcessWindowStyle.Hidden;
        Process p = Process.Start(psi);
        if (!p.WaitForExit(timeoutMs))
        {
            try { p.Kill(); } catch { }
            throw new TimeoutException("powershell timed out");
        }
    }

    static void CreateShortcut(string lnkPath, string target, string workDir, string description)
    {
        if (!File.Exists(target)) return;
        Directory.CreateDirectory(Path.GetDirectoryName(lnkPath));
        string ps1 = Path.Combine(Path.GetTempPath(), "qihang-lnk-" + Guid.NewGuid().ToString("N") + ".ps1");
        var sb = new StringBuilder();
        sb.AppendLine("$s = New-Object -ComObject WScript.Shell");
        sb.AppendLine("$l = $s.CreateShortcut(" + PsQuote(lnkPath) + ")");
        sb.AppendLine("$l.TargetPath = " + PsQuote(target));
        sb.AppendLine("$l.WorkingDirectory = " + PsQuote(workDir));
        sb.AppendLine("$l.WindowStyle = 1");
        sb.AppendLine("$l.Description = " + PsQuote(description));
        sb.AppendLine("$l.IconLocation = " + PsQuote(target + ",0"));
        sb.AppendLine("$l.Save()");
        File.WriteAllText(ps1, sb.ToString(), new UTF8Encoding(true));
        try { RunPowershell("-NoProfile -ExecutionPolicy Bypass -File \"" + ps1 + "\"", 30000); }
        finally { try { File.Delete(ps1); } catch { } }
    }

    static string PsQuote(string s) { return "'" + (s ?? "").Replace("'", "''") + "'"; }

    static string DesktopLnk(string name)
    {
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), name + ".lnk");
    }

    static string StartMenuDir()
    {
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "QihangOMS");
    }

    static string StartMenuLnk(string name)
    {
        return Path.Combine(StartMenuDir(), name + ".lnk");
    }

    static void WriteUninstallKey(string dest, string uninstallExe, string icon, long payloadBytes)
    {
        using (RegistryKey key = Registry.CurrentUser.CreateSubKey(Program.UninstallReg))
        {
            key.SetValue("DisplayName", Program.ProductName);
            key.SetValue("Publisher", "Qihang");
            key.SetValue("InstallLocation", dest);
            key.SetValue("DisplayIcon", icon);
            key.SetValue("UninstallString", "\"" + uninstallExe + "\" /UNINSTALL");
            key.SetValue("QuietUninstallString", "\"" + uninstallExe + "\" /UNINSTALL /S");
            key.SetValue("NoModify", 1, RegistryValueKind.DWord);
            key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
            key.SetValue("EstimatedSize", (int)Math.Min(int.MaxValue, payloadBytes / 1024), RegistryValueKind.DWord);
            string info = Path.Combine(dest, "BUILD-INFO.txt");
            if (File.Exists(info))
            {
                foreach (string line in File.ReadAllLines(info, Encoding.UTF8))
                {
                    if (line.StartsWith("revision:", StringComparison.OrdinalIgnoreCase))
                    {
                        key.SetValue("DisplayVersion", line.Substring("revision:".Length).Trim());
                        break;
                    }
                }
            }
        }
    }

    static void DeleteInstalledFiles(string dest, bool deleteData)
    {
        string self = Process.GetCurrentProcess().MainModule.FileName;
        bool skipSelf = false;
        foreach (string file in Directory.GetFiles(dest, "*", SearchOption.AllDirectories))
        {
            if (!deleteData && IsProtected(RelFrom(dest, file))) continue;
            if (string.Equals(file, self, StringComparison.OrdinalIgnoreCase)) { skipSelf = true; continue; }
            TryDelete(file);
        }
        string[] dirs = Directory.GetDirectories(dest, "*", SearchOption.AllDirectories);
        Array.Sort(dirs, (a, b) => b.Length.CompareTo(a.Length));
        foreach (string dir in dirs)
        {
            if (!deleteData && IsProtected(RelFrom(dest, dir))) continue;
            TryDeleteDir(dir);
        }
        if (deleteData) TryDeleteDir(dest);
        else
        {
            try { if (Directory.GetFileSystemEntries(dest).Length == 0) Directory.Delete(dest); } catch { }
        }
        if (skipSelf) ScheduleDelete(self, deleteData ? dest : null);
    }

    static void ScheduleDelete(string file, string dirIfEmpty)
    {
        string cmd = "ping 127.0.0.1 -n 2 >nul & del /f \"" + file + "\"";
        if (!string.IsNullOrEmpty(dirIfEmpty)) cmd += " & rmdir \"" + dirIfEmpty + "\"";
        try
        {
            Process.Start(new ProcessStartInfo("cmd.exe", "/c " + cmd)
            {
                CreateNoWindow = true,
                UseShellExecute = false,
                WindowStyle = ProcessWindowStyle.Hidden
            });
        }
        catch { }
    }

    static string RelFrom(string root, string path)
    {
        string r = Path.GetFullPath(root).TrimEnd('\\') + "\\";
        string p = Path.GetFullPath(path);
        if (p.StartsWith(r, StringComparison.OrdinalIgnoreCase)) return p.Substring(r.Length);
        return p;
    }

    static void TryDelete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }

    static void TryDeleteDir(string path)
    {
        try { if (Directory.Exists(path)) Directory.Delete(path, false); } catch { }
    }

    static void Report(Action<int, string> progress, int pct, string msg)
    {
        Log(pct + "% " + msg);
        if (progress != null) progress(pct, msg);
    }
}

internal sealed class Payload
{
    public string SelfPath;
    public long ZipOffset;
    public long ZipLength;
}

internal sealed class SliceStream : Stream
{
    readonly FileStream _inner;
    readonly long _start;
    readonly long _length;
    long _pos;

    public SliceStream(FileStream inner, long start, long length)
    {
        _inner = inner;
        _start = start;
        _length = length;
        _pos = 0;
        _inner.Seek(_start, SeekOrigin.Begin);
    }

    public override bool CanRead { get { return true; } }
    public override bool CanSeek { get { return true; } }
    public override bool CanWrite { get { return false; } }
    public override long Length { get { return _length; } }
    public override long Position
    {
        get { return _pos; }
        set { Seek(value, SeekOrigin.Begin); }
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        if (_pos >= _length) return 0;
        if (count > _length - _pos) count = (int)(_length - _pos);
        int n = _inner.Read(buffer, offset, count);
        _pos += n;
        return n;
    }

    public override long Seek(long offset, SeekOrigin origin)
    {
        long target = offset;
        if (origin == SeekOrigin.Current) target = _pos + offset;
        else if (origin == SeekOrigin.End) target = _length + offset;
        if (target < 0) target = 0;
        if (target > _length) target = _length;
        _inner.Seek(_start + target, SeekOrigin.Begin);
        _pos = target;
        return _pos;
    }

    public override void Flush() { }
    public override void SetLength(long value) { throw new NotSupportedException(); }
    public override void Write(byte[] buffer, int offset, int count) { throw new NotSupportedException(); }
}

internal sealed class MainForm : Form
{
    readonly Options _opt;
    readonly TextBox _dir;
    readonly CheckBox _desktop;
    readonly CheckBox _launch;
    readonly CheckBox _deleteData;
    readonly Label _hint;
    readonly Label _status;
    readonly ProgressBar _bar;
    readonly Button _go;
    readonly Button _browse;
    bool _busy;

    public MainForm(Options opt)
    {
        _opt = opt;
        Text = opt.Uninstall ? Program.ProductName + " 卸载" : Program.ProductName + " 安装";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(520, opt.Uninstall ? 280 : 300);
        Font = new Font("Segoe UI", 10f);
        BackColor = Color.White;

        var title = new Label();
        title.AutoSize = false;
        title.SetBounds(24, 18, 470, 28);
        title.Font = new Font("Segoe UI", 14f, FontStyle.Bold);
        title.Text = opt.Uninstall ? "卸载 " + Program.ProductName : "安装 " + Program.ProductName;
        Controls.Add(title);

        var pathLabel = new Label();
        pathLabel.AutoSize = true;
        pathLabel.Location = new Point(24, 60);
        pathLabel.Text = opt.Uninstall ? "安装目录" : "安装到纯英文路径（不要放桌面或中文目录）";
        Controls.Add(pathLabel);

        _dir = new TextBox();
        _dir.SetBounds(24, 86, 370, 28);
        _dir.Text = opt.Dir ?? "";
        _dir.TextChanged += delegate { RefreshState(); };
        Controls.Add(_dir);

        _browse = new Button();
        _browse.SetBounds(404, 85, 90, 30);
        _browse.Text = "浏览...";
        _browse.UseVisualStyleBackColor = true;
        _browse.Click += delegate { Browse(); };
        Controls.Add(_browse);

        _hint = new Label();
        _hint.AutoSize = false;
        _hint.SetBounds(24, 120, 470, 36);
        _hint.ForeColor = Color.FromArgb(180, 40, 40);
        Controls.Add(_hint);

        if (opt.Uninstall)
        {
            _deleteData = new CheckBox();
            _deleteData.AutoSize = true;
            _deleteData.Location = new Point(24, 158);
            _deleteData.Text = "同时删除数据库和配置（不可恢复）";
            _deleteData.Checked = opt.DeleteData;
            Controls.Add(_deleteData);
            _desktop = null;
            _launch = null;
        }
        else
        {
            _desktop = new CheckBox();
            _desktop.AutoSize = true;
            _desktop.Location = new Point(24, 158);
            _desktop.Text = "创建桌面快捷方式";
            _desktop.Checked = opt.DesktopShortcut;
            Controls.Add(_desktop);
            _launch = new CheckBox();
            _launch.AutoSize = true;
            _launch.Location = new Point(24, 184);
            _launch.Text = "安装完成后启动";
            _launch.Checked = opt.LaunchAfter;
            Controls.Add(_launch);
            _deleteData = null;
        }

        _bar = new ProgressBar();
        _bar.SetBounds(24, opt.Uninstall ? 198 : 220, 470, 18);
        _bar.Visible = false;
        Controls.Add(_bar);

        _status = new Label();
        _status.AutoSize = false;
        _status.SetBounds(24, opt.Uninstall ? 220 : 242, 300, 22);
        _status.ForeColor = Color.FromArgb(70, 70, 70);
        Controls.Add(_status);

        _go = new Button();
        _go.SetBounds(384, opt.Uninstall ? 236 : 258, 110, 32);
        _go.Text = opt.Uninstall ? "卸载" : "安装";
        _go.BackColor = Color.FromArgb(37, 99, 235);
        _go.ForeColor = Color.White;
        _go.FlatStyle = FlatStyle.Flat;
        _go.FlatAppearance.BorderSize = 0;
        _go.Click += delegate { StartWork(); };
        Controls.Add(_go);

        RefreshState();
    }

    void RefreshState()
    {
        if (_busy) return;
        string err = Engine.ValidateDir(_dir.Text);
        _hint.Text = err ?? "";
        _go.Enabled = err == null;
        if (!_opt.Uninstall && err == null && Engine.LooksInstalled(_dir.Text.Trim()))
        {
            _go.Text = "升级";
            _hint.ForeColor = Color.FromArgb(30, 90, 40);
            _hint.Text = "检测到已有安装，将覆盖程序文件并保留数据库和配置。";
        }
        else
        {
            _go.Text = _opt.Uninstall ? "卸载" : "安装";
            _hint.ForeColor = Color.FromArgb(180, 40, 40);
        }
    }

    void Browse()
    {
        using (var dlg = new FolderBrowserDialog())
        {
            dlg.Description = "选择安装目录";
            dlg.ShowNewFolderButton = true;
            if (Directory.Exists(_dir.Text)) dlg.SelectedPath = _dir.Text;
            if (dlg.ShowDialog(this) == DialogResult.OK) _dir.Text = dlg.SelectedPath;
        }
    }

    void StartWork()
    {
        string err = Engine.ValidateDir(_dir.Text);
        if (err != null)
        {
            MessageBox.Show(err, Program.ProductName, MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
        _opt.Dir = Path.GetFullPath(_dir.Text.Trim());
        if (_opt.Uninstall)
        {
            _opt.DeleteData = _deleteData != null && _deleteData.Checked;
            if (MessageBox.Show(
                    _opt.DeleteData
                        ? "将删除程序、数据库和配置，且不可恢复。确定卸载？"
                        : "将删除程序文件，并保留数据库（runtime\\mysql\\data）和配置。确定卸载？",
                    Program.ProductName, MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK)
                return;
        }
        else
        {
            _opt.DesktopShortcut = _desktop != null && _desktop.Checked;
            _opt.LaunchAfter = _launch != null && _launch.Checked;
            if (Engine.FindPayload() == null)
            {
                MessageBox.Show("安装包不完整，请重新下载 QihangOMS-Setup.exe", Program.ProductName, MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }
        }
        _busy = true;
        _dir.Enabled = false;
        _browse.Enabled = false;
        _go.Enabled = false;
        if (_desktop != null) _desktop.Enabled = false;
        if (_launch != null) _launch.Enabled = false;
        if (_deleteData != null) _deleteData.Enabled = false;
        _bar.Visible = true;
        _bar.Value = 0;
        var thread = new Thread(Work);
        thread.IsBackground = true;
        thread.Start();
    }

    void Work()
    {
        try
        {
            Action<int, string> cb = delegate(int pct, string msg)
            {
                BeginInvoke(new Action(delegate
                {
                    _bar.Value = Math.Max(0, Math.Min(100, pct));
                    _status.Text = msg;
                }));
            };
            if (_opt.Uninstall) Engine.Uninstall(_opt, cb);
            else Engine.Install(_opt, cb);
            BeginInvoke(new Action(delegate
            {
                MessageBox.Show(_opt.Uninstall ? "卸载完成。" : "安装完成。", Program.ProductName, MessageBoxButtons.OK, MessageBoxIcon.Information);
                Close();
            }));
        }
        catch (Exception ex)
        {
            Engine.Log("UI error " + ex);
            BeginInvoke(new Action(delegate
            {
                _busy = false;
                _dir.Enabled = true;
                _browse.Enabled = true;
                _go.Enabled = true;
                if (_desktop != null) _desktop.Enabled = true;
                if (_launch != null) _launch.Enabled = true;
                if (_deleteData != null) _deleteData.Enabled = true;
                RefreshState();
                MessageBox.Show(ex.Message, Program.ProductName, MessageBoxButtons.OK, MessageBoxIcon.Error);
            }));
        }
    }
}
