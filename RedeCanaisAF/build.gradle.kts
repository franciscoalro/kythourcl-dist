// Minimal build file

// v252: espera navigator.serviceWorker.controller==activated antes do recap — sem isso o
// bundle chama serverforms antes do SW enriquecer a request e cai em 204/e18b73c9=[]
// (harness com controller=true tocou ready=4; virgem controller=false falhou igual ao plugin).
cloudstream {
    version = 252
}
