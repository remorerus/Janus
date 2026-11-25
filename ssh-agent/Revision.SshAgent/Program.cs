using System.Text.Json;
using System.Drawing;
using System.Windows.Forms;

namespace Revision.SshAgent;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new ManagerForm());
    }
}

// Optional fields keep old command catalogs compatible.
internal sealed record SshCommand(
    string Id, string Name, string Phrase, string Shell, string Command,
    string Action = "script", string Executable = "", string Arguments = "",
    string WorkingDirectory = "", string ProcessName = "");

internal sealed class ManagerForm : Form
{
    private readonly string _dataDirectory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "REVisionSshAgent");
    private string CatalogPath => Path.Combine(_dataDirectory, "commands.json");
    private readonly List<SshCommand> _commands = new();
    private readonly DataGridView _grid = new();
    private readonly TextBox _name = new(), _phrase = new(), _command = new();
    private readonly TextBox _executable = new(), _arguments = new(), _workingDirectory = new(), _processName = new();
    private readonly ComboBox _shell = new(), _action = new();
    private readonly Label _status = new();
    private string? _editingId;

    public ManagerForm()
    {
        Text = "RE:Vision SSH Agent";
        Width = 1240; Height = 850; MinimumSize = new Size(1000, 700);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(24, 31, 42); ForeColor = Color.White;
        Font = new Font("Segoe UI", 9.5F);
        BuildLayout(); LoadCatalog();
    }

    private void BuildLayout()
    {
        var header = new Panel { Dock = DockStyle.Top, Height = 72, Padding = new Padding(22, 10, 22, 8), BackColor = Color.FromArgb(31, 41, 55) };
        header.Controls.Add(new Label { Text = "RE:Vision SSH Agent", AutoSize = true, Font = new Font("Segoe UI Semibold", 19F), ForeColor = Color.White, Location = new Point(18, 6) });
        header.Controls.Add(new Label { Text = "Самостоятельный SSH-исполнитель · каталог команд · без фоновой службы", AutoSize = true, ForeColor = Color.LightGray, Location = new Point(21, 42) });
        Controls.Add(header);

        var split = new SplitContainer { Dock = DockStyle.Fill, SplitterDistance = 560, BackColor = BackColor };
        Controls.Add(split); header.BringToFront();
        var left = new Panel { Dock = DockStyle.Fill, Padding = new Padding(14), AutoScroll = true };
        split.Panel1.Controls.Add(left);
        int y = 4;
        AddField(left, "Название команды", _name, ref y, 32);
        AddField(left, "Точная голосовая фраза", _phrase, ref y, 32);
        AddCombo(left, "Тип действия", _action, new[] {
            new KeyValuePair<string,string>("script","Сценарий PowerShell / CMD"),
            new KeyValuePair<string,string>("launch","Запустить приложение"),
            new KeyValuePair<string,string>("close","Закрыть приложение"),
            new KeyValuePair<string,string>("restart","Перезапустить приложение")
        }, ref y);
        _action.SelectedIndexChanged += (_, _) => UpdateEditorVisibility();
        AddField(left, "Путь к EXE / программе", _executable, ref y, 32);
        AddField(left, "Аргументы запуска (необязательно)", _arguments, ref y, 32);
        AddField(left, "Рабочая папка (необязательно)", _workingDirectory, ref y, 32);
        AddField(left, "Имя процесса для закрытия без .exe", _processName, ref y, 32);
        AddLabel(left, "Оболочка сценария", 8, y); y += 26;
        _shell.SetBounds(8, y, 500, 30); _shell.DropDownStyle = ComboBoxStyle.DropDownList;
        _shell.Items.AddRange(new object[] { "PowerShell", "CMD" }); _shell.SelectedIndex = 0; left.Controls.Add(_shell); y += 44;
        AddLabel(left, "Многострочный сценарий (для типа «Сценарий»)", 8, y); y += 25;
        _command.SetBounds(8, y, 500, 170); _command.Multiline = true; _command.ScrollBars = ScrollBars.Both;
        _command.AcceptsReturn = true; _command.AcceptsTab = true; _command.Font = new Font("Consolas", 9.5F); StyleInput(_command); left.Controls.Add(_command); y += 184;
        var save = MakeButton("Сохранить", 8, y, 130, 38, Color.FromArgb(37, 99, 235));
        save.Click += (_, _) => SaveCurrent(); left.Controls.Add(save);
        var clear = MakeButton("Новая команда", 148, y, 130, 38, Color.FromArgb(75, 85, 99));
        clear.Click += (_, _) => ClearEditor(); left.Controls.Add(clear);
        var delete = MakeButton("Удалить", 288, y, 100, 38, Color.FromArgb(153, 27, 27));
        delete.Click += (_, _) => DeleteSelected(); left.Controls.Add(delete);
        var hint = new Label { Text = "Действия выполняются на Windows-компьютере от имени SSH-пользователя. Для запуска указывай полный путь к EXE; для закрытия/перезапуска — имя процесса. Используй только доверенные команды.", AutoSize = false, ForeColor = Color.Khaki };
        hint.SetBounds(8, y + 50, 500, 65); left.Controls.Add(hint);

        var right = new Panel { Dock = DockStyle.Fill, Padding = new Padding(12) }; split.Panel2.Controls.Add(right);
        right.Controls.Add(new Label { Text = "Сохранённые команды", AutoSize = true, Font = new Font("Segoe UI Semibold", 14F), Location = new Point(8, 8) });
        _grid.SetBounds(8, 42, 610, 600); _grid.Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right;
        _grid.ReadOnly = true; _grid.AllowUserToAddRows = false; _grid.AllowUserToDeleteRows = false;
        _grid.MultiSelect = false; _grid.SelectionMode = DataGridViewSelectionMode.FullRowSelect;
        _grid.AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill;
        _grid.BackgroundColor = Color.FromArgb(31, 41, 55); _grid.BorderStyle = BorderStyle.None; _grid.RowHeadersVisible = false;
        _grid.SelectionChanged += (_, _) => LoadSelectedIntoEditor();
        _grid.Columns.Add("Name", "Название"); _grid.Columns.Add("Phrase", "Голосовая фраза"); _grid.Columns.Add("Action", "Действие");
        right.Controls.Add(_grid);
        var refresh = MakeButton("Обновить список", 8, 656, 150, 36, Color.FromArgb(75, 85, 99));
        refresh.Anchor = AnchorStyles.Left | AnchorStyles.Bottom; refresh.Click += (_, _) => LoadCatalog(); right.Controls.Add(refresh);
        var path = new Label { Text = "Каталог: " + CatalogPath, AutoSize = false, Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Bottom, ForeColor = Color.Silver };
        path.SetBounds(8, 704, 610, 30); right.Controls.Add(path);
        _status.SetBounds(8, 738, 610, 26); _status.Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Bottom; _status.ForeColor = Color.LightGreen; right.Controls.Add(_status);
        UpdateEditorVisibility();
    }

    private void AddField(Control parent, string label, TextBox box, ref int y, int height)
    {
        AddLabel(parent, label, 8, y); y += 24;
        box.SetBounds(8, y, 500, height); StyleInput(box); parent.Controls.Add(box); y += height + 10;
    }

    private void AddCombo(Control parent, string label, ComboBox combo, KeyValuePair<string,string>[] options, ref int y)
    {
        AddLabel(parent, label, 8, y); y += 24;
        combo.SetBounds(8, y, 500, 30); combo.DropDownStyle = ComboBoxStyle.DropDownList;
        foreach (var option in options) combo.Items.Add(option);
        combo.DisplayMember = "Value"; combo.ValueMember = "Key"; combo.SelectedIndex = 0;
        parent.Controls.Add(combo); y += 40;
    }

    private void UpdateEditorVisibility()
    {
        bool script = _action.SelectedItem is KeyValuePair<string,string> kv && kv.Key == "script";
        _shell.Enabled = script; _command.Enabled = script;
        _executable.Enabled = !script;
        _arguments.Enabled = _action.SelectedItem is KeyValuePair<string,string> a && a.Key == "launch";
        _workingDirectory.Enabled = _arguments.Enabled;
        _processName.Enabled = _action.SelectedItem is KeyValuePair<string,string> p && (p.Key == "close" || p.Key == "restart");
    }

    private static void AddLabel(Control parent, string text, int x, int y) =>
        parent.Controls.Add(new Label { Text = text, AutoSize = true, Location = new Point(x, y), ForeColor = Color.Gainsboro });
    private static void StyleInput(Control control) { control.BackColor = Color.FromArgb(17, 24, 39); control.ForeColor = Color.White; }
    private static Button MakeButton(string text, int x, int y, int width, int height, Color color)
    {
        var button = new Button { Text = text, BackColor = color, ForeColor = Color.White, FlatStyle = FlatStyle.Flat };
        button.SetBounds(x, y, width, height); return button;
    }

    private void LoadCatalog()
    {
        try
        {
            Directory.CreateDirectory(_dataDirectory); _commands.Clear();
            if (File.Exists(CatalogPath))
                _commands.AddRange(JsonSerializer.Deserialize<List<SshCommand>>(File.ReadAllText(CatalogPath)) ?? new());
            RefreshGrid(); SetStatus($"Загружено команд: {_commands.Count}");
        }
        catch (Exception ex) { SetStatus("Ошибка чтения каталога: " + ex.Message); }
    }

    private void RefreshGrid()
    {
        _grid.Rows.Clear();
        foreach (var item in _commands)
        {
            string action = item.Action switch { "launch" => "Запуск", "close" => "Закрытие", "restart" => "Перезапуск", _ => item.Shell.Equals("cmd", StringComparison.OrdinalIgnoreCase) ? "Сценарий CMD" : "Сценарий PS" };
            _grid.Rows.Add(item.Name, item.Phrase, action);
        }
    }

    private void LoadSelectedIntoEditor()
    {
        if (_grid.CurrentRow is null || _grid.CurrentRow.Index < 0 || _grid.CurrentRow.Index >= _commands.Count) return;
        var item = _commands[_grid.CurrentRow.Index];
        _editingId = item.Id; _name.Text = item.Name; _phrase.Text = item.Phrase;
        _action.SelectedItem = _action.Items.Cast<KeyValuePair<string,string>>().FirstOrDefault(x => x.Key == (item.Action ?? "script"));
        _shell.SelectedIndex = item.Shell.Equals("cmd", StringComparison.OrdinalIgnoreCase) ? 1 : 0;
        _command.Text = item.Command; _executable.Text = item.Executable ?? "";
        _arguments.Text = item.Arguments ?? ""; _workingDirectory.Text = item.WorkingDirectory ?? "";
        _processName.Text = item.ProcessName ?? ""; UpdateEditorVisibility();
    }

    private void SaveCurrent()
    {
        var name = _name.Text.Trim(); var phrase = _phrase.Text.Trim();
        var action = (_action.SelectedItem is KeyValuePair<string,string> selected ? selected.Key : "script");
        var command = _command.Text; var executable = _executable.Text.Trim();
        var processName = _processName.Text.Trim();
        if (name.Length is < 1 or > 80 || phrase.Length is < 1 or > 160)
        { SetStatus("Заполни название и точную голосовую фразу."); return; }
        if (_commands.Any(x => x.Id != _editingId && x.Phrase.Equals(phrase, StringComparison.OrdinalIgnoreCase)))
        { SetStatus("Такая голосовая фраза уже существует."); return; }
        if (action == "script" && string.IsNullOrWhiteSpace(command))
        { SetStatus("Для сценария добавь текст PowerShell или CMD."); return; }
        if (action != "script" && string.IsNullOrWhiteSpace(executable))
        { SetStatus("Укажи полный путь к исполняемому файлу."); return; }
        if ((action == "close" || action == "restart") && string.IsNullOrWhiteSpace(processName))
        { SetStatus("Укажи имя процесса без .exe для закрытия/перезапуска."); return; }
        var updated = new SshCommand(_editingId ?? Guid.NewGuid().ToString("N"), name, phrase,
            _shell.SelectedIndex == 1 ? "cmd" : "powershell", command,
            action, executable, _arguments.Text.Trim(), _workingDirectory.Text.Trim(), processName);
        if (_editingId is null) _commands.Add(updated);
        else { var index = _commands.FindIndex(x => x.Id == _editingId); if (index >= 0) _commands[index] = updated; }
        Persist(); RefreshGrid(); ClearEditor();
    }

    private void DeleteSelected()
    {
        if (_editingId is null) { SetStatus("Сначала выбери команду из списка."); return; }
        var item = _commands.FirstOrDefault(x => x.Id == _editingId); if (item is null) return;
        if (MessageBox.Show($"Удалить команду «{item.Name}»?", "Подтверждение", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
        _commands.Remove(item); Persist(); RefreshGrid(); ClearEditor();
    }

    private void Persist()
    {
        try
        {
            Directory.CreateDirectory(_dataDirectory); var temp = CatalogPath + ".tmp";
            File.WriteAllText(temp, JsonSerializer.Serialize(_commands, new JsonSerializerOptions { WriteIndented = true }));
            File.Move(temp, CatalogPath, true); SetStatus($"Сохранено. Команд: {_commands.Count}");
        }
        catch (Exception ex) { SetStatus("Ошибка сохранения: " + ex.Message); }
    }

    private void ClearEditor()
    {
        _editingId = null; _name.Clear(); _phrase.Clear(); _command.Clear(); _executable.Clear();
        _arguments.Clear(); _workingDirectory.Clear(); _processName.Clear(); _action.SelectedIndex = 0;
        _shell.SelectedIndex = 0; _grid.ClearSelection(); UpdateEditorVisibility();
    }

    private void SetStatus(string value) => _status.Text = value;
}
