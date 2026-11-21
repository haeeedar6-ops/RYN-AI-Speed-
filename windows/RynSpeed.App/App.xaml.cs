using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Xaml;
using RynSpeed.Core;
using RynSpeed.Database;
using RynSpeed.Security;

namespace RynSpeed.App;

public partial class App : Application
{
    public IServiceProvider Services { get; }
    private Window? _window;

    public App()
    {
        InitializeComponent();
        Services = ConfigureServices();
    }

    private static IServiceProvider ConfigureServices()
    {
        var services = new ServiceCollection();
        var appDataDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "RYN-AI-Speed");

        services.AddSingleton<ModuleEventBus>();
        services.AddSingleton(new DpapiSecretStore(Path.Combine(appDataDir, "vault")));
        services.AddSingleton(sp => new RynSqliteConnectionFactory(
            Path.Combine(appDataDir, "ryn_ai_speed.db"),
            sp.GetRequiredService<DpapiSecretStore>()));

        return services.BuildServiceProvider();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        _window = new MainWindow();
        _window.Activate();
    }
}
