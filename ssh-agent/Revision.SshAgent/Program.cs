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

internal sealed record SshCommand(string Id, string Name, string Phrase, string Shell, string Command);

internal sealed class ManagerForm : Form
{
    private readonly string _dataDirectory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "REVisionSshAgent");
    private string CatalogPath => Path.Combine(_dataDirectory, "commands.json");
    private readonly List<SshCommand> _commands = new();
    private readonly DataGridView _grid = new();
    private readonly TextBox _name = new();
    private readonly TextBox _phrase = new();
    private readonly ComboBox _shell = new();
    private readonly TextBox _command = new();
    private readonly Label _status = new();
    private string? _editingId;

    public ManagerForm()
    {
        Text = "RE:Vision SSH Agent";
        Width = 1120;
        Height = 760;
        MinimumSize = new Size(900, 620);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(24, 31, 42);
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 10F);
        BuildLayout();
        LoadCatalog();
    }

    private void BuildLayout()
    {
        var header = new Panel { Dock = DockStyle.Top, Height = 76, Padding = new Padding(22, 12, 22, 8), BackColor = Color.FromArgb(31, 41, 55) };
        var title = new Label { Text = "RE:Vision SSH Agent", AutoSize = true, Font = new Font("Segoe UI Semibold", 19F), ForeColor = Color.White, Location = new Point(18, 8) };
        var subtitle = new Label { Text = "Локальный каталог голосовых команд · без фоновой службы", AutoSize = true, ForeColor = Color.LightGray, Location = new Point(21, 43) };
        header.Controls.Add(title); header.Controls.Add(subtitle);

        var split = new SplitContainer { Dock = DockStyle.Fill, SplitterDistance = 430, Padding = new Padding(14), BackColor = BackColor };
        Controls.Add(split); Controls.Add(header); header.BringToFront();

        var left = new Panel { Dock = DockStyle.Fill, Padding = new Padding(10) };
        split.Panel1.Controls.Add(left);
        AddLabel(left, "Название команды", 8, 8);
        _name.SetBounds(8, 34, 380, 32); StyleInput(_name); left.Controls.Add(_name);
        AddLabel(left, "Точная голосовая фраза", 8, 82);
        _phrase.SetBounds(8, 108, 380, 32); StyleInput(_phrase); left.Controls.Add(_phrase);
        AddLabel(left, "Оболочка выполнения", 8, 156);
        _shell.SetBounds(8, 182, 380, 32); _shell.DropDownStyle = ComboBoxStyle.DropDownList;
        _shell.Items.AddRange(new object[] { "PowerShell", "CMD" }); _shell.SelectedIndex = 0; left.Controls.Add(_shell);
        AddLabel(left, "Команда или сценарий", 8, 230);
        _command.SetBounds(8, 256, 380, 230); _command.Multiline = true; _command.ScrollBars = ScrollBars.Both;
        _command.AcceptsReturn = true; _command.AcceptsTab = true; _command.Font = new Font("Consolas", 10F);
        StyleInput(_command); left.Controls.Add(_command);

        var save = MakeButton("Сохранить команду", 8, 506, 180, 40, Color.FromArgb(37, 99, 235));
        save.Click += (_, _) => SaveCurrent(); left.Controls.Add(save);
        var clear = MakeButton("Новая", 198, 506, 90, 40, Color.FromArgb(75, 85, 99));
        clear.Click += (_, _) => ClearEditor(); left.Controls.Add(clear);
        var delete = MakeButton("Удалить", 298, 506, 90, 40, Color.FromArgb(153, 27, 27));
        delete.Click += (_, _) => DeleteSelected(); left.Controls.Add(delete);

        var hint = new Label { Text = "Команды запускаются по точному совпадению голосовой фразы и выполняются от имени SSH-пользователя Windows. Добавляй только команды, которым доверяешь.", AutoSize = false, ForeColor = Color.Khaki };
        hint.SetBounds(8, 564, 380, 70); left.Controls.Add(hint);

        var right = new Panel { Dock = DockStyle.Fill, Padding = new Padding(10) };
        split.Panel2.Controls.Add(right);
        var listTitle = new Label { Text = "Сохранённые команды", AutoSize = true, Font = new Font("Segoe UI Semibold", 14F), Location = new Point(8, 8) };
        right.Controls.Add(listTitle);
        _grid.SetBounds(8, 42, 610, 530); _grid.Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right;
        _grid.ReadOnly = true; _grid.AllowUserToAddRows = false; _grid.AllowUserToDeleteRows = false;
        _grid.MultiSelect = false; _grid.SelectionMode = DataGridViewSelectionMode.FullRowSelect;
        _grid.AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill;
        _grid.BackgroundColor = Color.FromArgb(31, 41, 55); _grid.BorderStyle = BorderStyle.None;
        _grid.RowHeadersVisible = false; _grid.SelectionChanged += (_, _) => LoadSelectedIntoEditor();
        _grid.Columns.Add("Name", "Название"); _grid.Columns.Add("Phrase", "Голосовая фраза");
        _grid.Columns.Add("Shell", "Оболочка");
        _grid.Columns[0].FillWeight = 80; _grid.Columns[1].FillWeight = 100; _grid.Columns[2].FillWeight = 45;
        right.Controls.Add(_grid);
        var refresh = MakeButton("Обновить список", 8, 586, 150, 38, Color.FromArgb(75, 85, 99));
        refresh.Click += (_, _) => LoadCatalog(); right.Controls.Add(refresh);
        var path = new Label { Text = "Файл: " + CatalogPath, AutoSize = false, Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Bottom, ForeColor = Color.Silver };
        path.SetBounds(8, 632, 610, 34); right.Controls.Add(path);
        _status.SetBounds(8, 675, 610, 28); _status.Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Bottom;
        _status.ForeColor = Color.LightGreen; right.Controls.Add(_status);
    }

    private static void AddLabel(Control parent, string text, int x, int y) =>
        parent.Controls.Add(new Label { Text = text, AutoSize = true, Location = new Point(x, y), ForeColor = Color.Gainsboro });

    private static void StyleInput(Control control)
    {
        control.BackColor = Color.FromArgb(17, 24, 39);
        control.ForeColor = Color.White;
    }

    private static Button MakeButton(string text, int x, int y, int width, int height, Color color) => CreateButton(text, x, y, width, height, color);

    private static Button CreateButton(string text, int x, int y, int width, int height, Color color)
    {
        var button = new Button { Text = text, BackColor = color, ForeColor = Color.White, FlatStyle = FlatStyle.Flat };
        button.SetBounds(x, y, width, height);
        return button;
    }

    private void LoadCatalog()
    {
        try
        {
            Directory.CreateDirectory(_dataDirectory);
            _commands.Clear();
            if (File.Exists(CatalogPath))
                _commands.AddRange(JsonSerializer.Deserialize<List<SshCommand>>(File.ReadAllText(CatalogPath)) ?? new());
            RefreshGrid();
            SetStatus($"Загружено команд: {_commands.Count}");
        }
        catch (Exception ex) { SetStatus("Ошибка чтения каталога: " + ex.Message); }
    }

    private void RefreshGrid()
    {
        _grid.Rows.Clear();
        foreach (var item in _commands)
            _grid.Rows.Add(item.Name, item.Phrase, item.Shell.Equals("cmd", StringComparison.OrdinalIgnoreCase) ? "CMD" : "PowerShell");
    }

    private void LoadSelectedIntoEditor()
    {
        if (_grid.CurrentRow is null || _grid.CurrentRow.Index < 0 || _grid.CurrentRow.Index >= _commands.Count) return;
        var item = _commands[_grid.CurrentRow.Index];
        _editingId = item.Id; _name.Text = item.Name; _phrase.Text = item.Phrase;
        _shell.SelectedIndex = item.Shell.Equals("cmd", StringComparison.OrdinalIgnoreCase) ? 1 : 0;
        _command.Text = item.Command;
    }

    private void SaveCurrent()
    {
        var name = _name.Text.Trim(); var phrase = _phrase.Text.Trim(); var command = _command.Text.Trim();
        var shell = _shell.SelectedIndex == 1 ? "cmd" : "powershell";
        if (name.Length is < 1 or > 80 || phrase.Length is < 1 or > 160 || command.Length is < 1 or > 4000)
        { SetStatus("Заполни название, фразу и команду. Ограничения: 80 / 160 / 4000 символов."); return; }
        if (_commands.Any(x => x.Id != _editingId && x.Phrase.Equals(phrase, StringComparison.OrdinalIgnoreCase)))
        { SetStatus("Такая голосовая фраза уже существует."); return; }
        var updated = new SshCommand(_editingId ?? Guid.NewGuid().ToString("N"), name, phrase, shell, command);
        if (_editingId is null) _commands.Add(updated);
        else { var index = _commands.FindIndex(x => x.Id == _editingId); if (index >= 0) _commands[index] = updated; }
        Persist(); RefreshGrid(); ClearEditor();
    }

    private void DeleteSelected()
    {
        if (_editingId is null) { SetStatus("Сначала выбери команду из списка."); return; }
        var item = _commands.FirstOrDefault(x => x.Id == _editingId);
        if (item is null) return;
        if (MessageBox.Show($"Удалить команду «{item.Name}»?", "Подтверждение", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
        _commands.Remove(item); Persist(); RefreshGrid(); ClearEditor();
    }

    private void Persist()
    {
        try
        {
            Directory.CreateDirectory(_dataDirectory);
            var temp = CatalogPath + ".tmp";
            File.WriteAllText(temp, JsonSerializer.Serialize(_commands, new JsonSerializerOptions { WriteIndented = true }));
            File.Move(temp, CatalogPath, true);
            SetStatus($"Сохранено. Команд: {_commands.Count}");
        }
        catch (Exception ex) { SetStatus("Ошибка сохранения: " + ex.Message); }
    }

    private void ClearEditor()
    {
        _editingId = null; _name.Clear(); _phrase.Clear(); _command.Clear(); _shell.SelectedIndex = 0; _grid.ClearSelection();
    }

    private void SetStatus(string value) => _status.Text = value;
}
