package di
import data.db.Db
import data.db.GraphTemplateRepository
import data.db.templates.TemplateSeederJvm
import org.koin.dsl.module
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import presentation.PlcEditorController

val jvmModule = module {

    // config
    single { AppConfig(dbPath = "./simpleplc.db", templatesPath = "./templates.json") }

    // coroutine scope (аналог viewModelScope)
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    // DB init (один раз)
    single {
        val cfg = get<AppConfig>()
        Db.connect(cfg.dbPath)
        true
    }

    // repo
    single { GraphTemplateRepository() }

    // seeder
    single { TemplateSeederJvm(repo = get(), externalTemplatesPath = get<AppConfig>().templatesPath) }

    // editor controller (замена ViewModel)
    single { PlcEditorController(scope = get(), templates = get(), seeder = get()) }
}

data class AppConfig(
    val dbPath: String,
    val templatesPath: String? = null
)
