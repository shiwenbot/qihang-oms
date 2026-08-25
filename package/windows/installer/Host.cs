using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

internal static class Program
{
    public const string Product = "启航电商 OMS";
    public const string WebView2RuntimeUrl = "https://go.microsoft.com/fwlink/p/?LinkId=2124703";
    const string MutexName = @"Local\QihangOMS.Desktop";

    [STAThread]
    static int Main(string[] args)
    {
        bool stopOnly = false;
        foreach (string a in args)
        {
            if (a != null && (a.Equals("/stop", StringComparison.OrdinalIgnoreCase) || a.Equals("-stop", StringComparison.OrdinalIgnoreCase)))
                stopOnly = true;
        }
        if (stopOnly)
        {
            try { Scripts.Stop(null); return 0; }
            catch (Exception ex) { Console.Error.WriteLine(ex.Message); return 1; }
        }

        bool created;
        using (var mutex = new Mutex(true, MutexName, out created))
        {
            if (!created)
            {
                Native.FocusExisting();
                return 0;
            }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new HostForm());
            return 0;
        }
    }
}

internal static class Native
{
    const int SW_RESTORE = 9;
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    static extern IntPtr FindWindow(string lpClassName, string lpWindowName);
    [DllImport("user32.dll")]
    static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")]
    static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    public static void FocusExisting()
    {
        IntPtr hwnd = FindWindow(null, Program.Product);
        if (hwnd == IntPtr.Zero) return;
        ShowWindow(hwnd, SW_RESTORE);
        SetForegroundWindow(hwnd);
    }
}

internal static class Scripts
{
    public static string AppHome()
    {
        return Path.GetDirectoryName(Process.GetCurrentProcess().MainModule.FileName);
    }

    public static string Powershell()
    {
        return Path.Combine(Environment.SystemDirectory, @"WindowsPowerShell\v1.0\powershell.exe");
    }

    public static string ScriptPath(string name)
    {
        return Path.Combine(AppHome(), "package", "windows", name);
    }

    public static Process Start(Action<string> onLine)
    {
        return Launch("Start-QihangOms.ps1", onLine);
    }

    public static void Stop(Action<string> onLine)
    {
        Process p = Launch("Stop-QihangOms.ps1", onLine);
        if (!p.WaitForExit(60000))
        {
            try { p.Kill(); } catch { }
            throw new TimeoutException("stop timed out");
        }
        if (p.ExitCode != 0) throw new InvalidOperationException("stop failed, exit " + p.ExitCode);
    }

    static Process Launch(string scriptName, Action<string> onLine)
    {
        string script = ScriptPath(scriptName);
        if (!File.Exists(script)) throw new InvalidOperationException("incomplete package: " + script);
        var psi = new ProcessStartInfo();
        psi.FileName = Powershell();
        psi.Arguments = "-NoProfile -ExecutionPolicy Bypass -File \"" + script + "\"";
        psi.WorkingDirectory = AppHome();
        psi.UseShellExecute = false;
        psi.CreateNoWindow = true;
        psi.RedirectStandardOutput = true;
        psi.RedirectStandardError = true;
        psi.StandardOutputEncoding = Encoding.GetEncoding(0);
        psi.StandardErrorEncoding = Encoding.GetEncoding(0);
        psi.EnvironmentVariables["QIHANGOMS_NO_BROWSER"] = "1";
        Process p = new Process();
        p.StartInfo = psi;
        p.EnableRaisingEvents = true;
        DataReceivedEventHandler handler = delegate(object sender, DataReceivedEventArgs e)
        {
            if (e.Data != null && onLine != null) onLine(e.Data);
        };
        p.OutputDataReceived += handler;
        p.ErrorDataReceived += handler;
        if (!p.Start()) throw new InvalidOperationException("failed to start powershell");
        p.BeginOutputReadLine();
        p.BeginErrorReadLine();
        return p;
    }
}

internal sealed class HostForm : Form
{
    readonly Panel _bar;
    readonly Panel _content;
    readonly Label _status;
    readonly LinkLabel _url;
    readonly Button _open;
    readonly Button _logBtn;
    readonly TextBox _log;
    readonly Panel _missing;
    readonly Label _missingText;
    readonly WebView2 _web;
    readonly NotifyIcon _tray;
    Process _startProcess;
    string _omsUrl = "http://127.0.0.1:8086";
    bool _exiting;
    bool _closeConfirmed;
    bool _webReady;
    bool _navigated;
    bool _logPinned = true;
    Task _prepareWeb;

    public HostForm()
    {
        Text = Program.Product;
        FormBorderStyle = FormBorderStyle.Sizable;
        MaximizeBox = true;
        MinimizeBox = true;
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(960, 640);
        Size = new Size(1280, 800);
        Font = new Font("Segoe UI", 10f);
        BackColor = Color.White;
        try { Icon = Icon.ExtractAssociatedIcon(Process.GetCurrentProcess().MainModule.FileName); } catch { }

        _bar = new Panel();
        _bar.Dock = DockStyle.Top;
        _bar.Height = 44;
        _bar.BackColor = Color.FromArgb(248, 250, 252);
        Controls.Add(_bar);

        _status = new Label();
        _status.AutoSize = false;
        _status.SetBounds(12, 10, 160, 24);
        _status.Font = new Font("Segoe UI", 10f, FontStyle.Bold);
        _status.Text = "正在启动…";
        _status.ForeColor = Color.FromArgb(180, 120, 20);
        _bar.Controls.Add(_status);

        _url = new LinkLabel();
        _url.AutoSize = false;
        _url.SetBounds(180, 12, 420, 22);
        _url.LinkClicked += delegate { OpenBrowser(); };
        _bar.Controls.Add(_url);

        _open = new Button();
        _open.SetBounds(620, 8, 110, 28);
        _open.Text = "浏览器打开";
        _open.Enabled = false;
        _open.FlatStyle = FlatStyle.Flat;
        _open.Click += delegate { OpenBrowser(); };
        _bar.Controls.Add(_open);

        _logBtn = new Button();
        _logBtn.SetBounds(740, 8, 70, 28);
        _logBtn.Text = "日志";
        _logBtn.FlatStyle = FlatStyle.Flat;
        _logBtn.Click += delegate { ToggleLog(); };
        _bar.Controls.Add(_logBtn);

        var hint = new Label();
        hint.AutoSize = false;
        hint.Anchor = AnchorStyles.Top | AnchorStyles.Right;
        hint.SetBounds(820, 12, 430, 22);
        hint.TextAlign = ContentAlignment.MiddleRight;
        hint.ForeColor = Color.FromArgb(120, 120, 120);
        hint.Text = "关闭窗口 = 停止全部服务";
        _bar.Controls.Add(hint);
        Resize += delegate { hint.Left = Math.Max(820, _bar.ClientSize.Width - 442); };

        _log = new TextBox();
        _log.Multiline = true;
        _log.ReadOnly = true;
        _log.ScrollBars = ScrollBars.Vertical;
        _log.Dock = DockStyle.Bottom;
        _log.Height = 160;
        _log.Font = new Font("Consolas", 9f);
        _log.BackColor = Color.FromArgb(248, 250, 252);
        _log.BorderStyle = BorderStyle.FixedSingle;
        Controls.Add(_log);

        _content = new Panel();
        _content.Dock = DockStyle.Fill;
        _content.BackColor = Color.White;
        Controls.Add(_content);
        _content.BringToFront();

        _missing = new Panel();
        _missing.Dock = DockStyle.Fill;
        _missing.Visible = false;
        _missing.BackColor = Color.White;
        _missingText = new Label();
        _missingText.AutoSize = false;
        _missingText.Dock = DockStyle.Top;
        _missingText.Height = 90;
        _missingText.Padding = new Padding(24, 24, 24, 8);
        _missing.Controls.Add(_missingText);
        var installRt = new Button();
        installRt.SetBounds(24, 110, 180, 32);
        installRt.Text = "安装 WebView2 运行时";
        installRt.Click += delegate { OpenExternal(Program.WebView2RuntimeUrl); };
        _missing.Controls.Add(installRt);
        var useBrowser = new Button();
        useBrowser.SetBounds(216, 110, 140, 32);
        useBrowser.Text = "改用浏览器打开";
        useBrowser.Click += delegate { OpenBrowser(); };
        _missing.Controls.Add(useBrowser);
        _content.Controls.Add(_missing);

        _web = new WebView2();
        _web.Dock = DockStyle.Fill;
        _web.CreationProperties = new CoreWebView2CreationProperties();
        _web.CreationProperties.UserDataFolder = Path.Combine(Scripts.AppHome(), "config", "webview2");
        _content.Controls.Add(_web);
        _web.BringToFront();

        _tray = new NotifyIcon();
        _tray.Text = Program.Product;
        _tray.Visible = true;
        try { _tray.Icon = Icon; } catch { }
        var menu = new ContextMenu();
        menu.MenuItems.Add("打开界面", delegate { Restore(); });
        menu.MenuItems.Add("浏览器打开", delegate { OpenBrowser(); });
        menu.MenuItems.Add("-");
        menu.MenuItems.Add("退出并停止服务", delegate { RequestExit(); });
        _tray.ContextMenu = menu;
        _tray.DoubleClick += delegate { Restore(); };

        Load += OnLoad;
        FormClosing += OnFormClosing;
    }

    void OnLoad(object sender, EventArgs e)
    {
        _prepareWeb = PrepareWebView();
        BeginStart();
    }

    async Task PrepareWebView()
    {
        try
        {
            string ver = null;
            try { ver = CoreWebView2Environment.GetAvailableBrowserVersionString(); }
            catch (WebView2RuntimeNotFoundException) { ver = null; }
            if (string.IsNullOrEmpty(ver))
            {
                ShowMissingRuntime("本机未安装 Microsoft Edge WebView2 运行时。");
                return;
            }
            await _web.EnsureCoreWebView2Async();
            _web.CoreWebView2.Settings.AreDevToolsEnabled = false;
            _web.CoreWebView2.Settings.AreDefaultContextMenusEnabled = true;
            _web.CoreWebView2.Settings.AreBrowserAcceleratorKeysEnabled = true;
            _web.CoreWebView2.NewWindowRequested -= OnNewWindow;
            _web.CoreWebView2.NewWindowRequested += OnNewWindow;
            _web.CoreWebView2.NavigationCompleted -= OnNavigated;
            _web.CoreWebView2.NavigationCompleted += OnNavigated;
            _webReady = true;
            Append("窗口内浏览器已就绪。");
        }
        catch (WebView2RuntimeNotFoundException)
        {
            ShowMissingRuntime("本机未安装 Microsoft Edge WebView2 运行时。");
        }
        catch (Exception ex)
        {
            ShowMissingRuntime("无法初始化窗口内浏览器：" + ex.Message);
        }
    }

    void BeginStart()
    {
        Append("正在启动 MySQL / Redis / 采集服务 / OMS …");
        var t = new Thread(StartWorker);
        t.IsBackground = true;
        t.Start();
    }

    void StartWorker()
    {
        try
        {
            Process p = Scripts.Start(Append);
            _startProcess = p;
            p.WaitForExit();
            _startProcess = null;
            if (_exiting) return;
            if (p.ExitCode == 0)
                Ui(delegate { MarkRunning(); NavigateApp(); });
            else
            {
                Ui(delegate
                {
                    _status.Text = "启动失败";
                    _status.ForeColor = Color.FromArgb(180, 40, 40);
                    Append("启动失败（exit " + p.ExitCode + "）。可关闭窗口后重试。");
                });
            }
        }
        catch (Exception ex)
        {
            if (_exiting) return;
            Ui(delegate
            {
                _status.Text = "启动失败";
                _status.ForeColor = Color.FromArgb(180, 40, 40);
                Append(ex.Message);
            });
        }
    }

    void MarkRunning()
    {
        _status.Text = "运行中";
        _status.ForeColor = Color.FromArgb(22, 130, 60);
        _url.Text = _omsUrl;
        _open.Enabled = true;
    }

    async void NavigateApp()
    {
        if (_navigated) return;
        try
        {
            if (_prepareWeb != null) await _prepareWeb;
        }
        catch { }
        if (!_webReady || _web.CoreWebView2 == null)
        {
            _open.Enabled = true;
            Append("窗口内打开失败，请点右上角“浏览器打开”。");
            return;
        }
        _navigated = true;
        Append("正在窗口内打开 " + _omsUrl);
        CollapseLog();
        _web.BringToFront();
        _web.CoreWebView2.Navigate(_omsUrl);
    }

    void OnNavigated(object sender, CoreWebView2NavigationCompletedEventArgs e)
    {
        if (e.IsSuccess) Append("界面已加载。");
        else Append("页面加载失败（" + e.WebErrorStatus + "），可点“浏览器打开”。");
    }

    void ShowMissingRuntime(string message)
    {
        if (InvokeRequired) { BeginInvoke(new Action(delegate { ShowMissingRuntime(message); })); return; }
        _missingText.Text = message + Environment.NewLine + Environment.NewLine
            + "可安装 WebView2 后重新打开本程序，或点“改用浏览器打开”。";
        _missing.Visible = true;
        _missing.BringToFront();
        _open.Enabled = true;
        Append(message);
    }

    void OnNewWindow(object sender, CoreWebView2NewWindowRequestedEventArgs e)
    {
        e.Handled = true;
        string uri = e.Uri;
        if (IsAppUrl(uri) && _web.CoreWebView2 != null)
            _web.CoreWebView2.Navigate(uri);
        else
            OpenExternal(uri);
    }

    bool IsAppUrl(string uri)
    {
        if (string.IsNullOrEmpty(uri)) return false;
        try
        {
            var target = new Uri(uri);
            var home = new Uri(_omsUrl);
            return string.Equals(target.Host, home.Host, StringComparison.OrdinalIgnoreCase)
                && target.Port == home.Port;
        }
        catch { return false; }
    }

    void ToggleLog()
    {
        _logPinned = !_logPinned;
        _log.Visible = _logPinned;
        _log.Height = _logPinned ? 160 : 0;
    }

    void CollapseLog()
    {
        _logPinned = false;
        _log.Visible = false;
        _log.Height = 0;
    }

    void OnFormClosing(object sender, FormClosingEventArgs e)
    {
        if (_closeConfirmed)
        {
            try { _tray.Visible = false; } catch { }
            return;
        }
        e.Cancel = true;
        RequestExit();
    }

    void RequestExit()
    {
        if (_exiting) return;
        var r = MessageBox.Show(this, "关闭窗口将停止全部服务，确定退出？", Program.Product,
            MessageBoxButtons.YesNo, MessageBoxIcon.Question, MessageBoxDefaultButton.Button2);
        if (r != DialogResult.Yes) return;
        _exiting = true;
        _status.Text = "正在停止…";
        _status.ForeColor = Color.FromArgb(180, 120, 20);
        _open.Enabled = false;
        var t = new Thread(StopWorker);
        t.IsBackground = true;
        t.Start();
    }

    void StopWorker()
    {
        try
        {
            Process start = _startProcess;
            if (start != null && !start.HasExited)
            {
                try { start.Kill(); } catch { }
                try { start.WaitForExit(5000); } catch { }
            }
            Append("正在停止本安装目录启动的服务…");
            Scripts.Stop(Append);
            Append("已停止。");
        }
        catch (Exception ex)
        {
            Append("停止时出错：" + ex.Message);
        }
        Ui(delegate
        {
            _closeConfirmed = true;
            try { _tray.Visible = false; } catch { }
            Close();
        });
    }

    void OpenBrowser()
    {
        OpenExternal(_omsUrl);
    }

    static void OpenExternal(string uri)
    {
        if (string.IsNullOrEmpty(uri)) return;
        try { Process.Start(new ProcessStartInfo(uri) { UseShellExecute = true }); }
        catch { }
    }

    void Restore()
    {
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
    }

    void Append(string line)
    {
        if (string.IsNullOrEmpty(line)) return;
        string url = ExtractUrl(line);
        if (url != null) _omsUrl = url;
        bool ready = line.IndexOf("Started:", StringComparison.OrdinalIgnoreCase) >= 0
            || line.IndexOf("already running at", StringComparison.OrdinalIgnoreCase) >= 0;
        Ui(delegate
        {
            if (_log.TextLength > 0) _log.AppendText(Environment.NewLine);
            _log.AppendText(DateTime.Now.ToString("HH:mm:ss") + "  " + line);
            if (ready)
            {
                MarkRunning();
                NavigateApp();
            }
        });
    }

    static string ExtractUrl(string line)
    {
        Match m = Regex.Match(line, @"https?://[^\s]+");
        return m.Success ? m.Value.TrimEnd('.', ',', ';') : null;
    }

    void Ui(Action action)
    {
        if (IsDisposed) return;
        if (InvokeRequired) { try { BeginInvoke(action); } catch { } }
        else action();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            try { _tray.Visible = false; _tray.Dispose(); } catch { }
            try { _web.Dispose(); } catch { }
        }
        base.Dispose(disposing);
    }
}
