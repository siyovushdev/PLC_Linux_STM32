package di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module
import presentation.PlcMemoryController
import presentation.PlcStatusController

val cliModule = module {
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { PlcMemoryController(scope = get()) }
    single { PlcStatusController(scope = get()) }
}
